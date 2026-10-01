package com.cslsm.web.reserves;

import com.cslsm.web.finance.FinanceModels.BankAccount;
import com.cslsm.web.finance.FinanceRepository;
import com.cslsm.web.reserves.MovementRepository.StoredMovement;
import com.cslsm.web.reserves.MovementRepository.Table;
import com.cslsm.web.support.Actor;
import com.cslsm.web.support.AuditService;
import com.cslsm.web.support.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Admin actions on the reception balance and the safe. Every action is one row in the
 * movement tables the desktop app already uses, plus an audit entry.
 */
@Service
public class ReserveService
{
	/** What the admin is recording, and how it maps onto the movement tables. */
	public enum Action
	{
		RECEPTION_TO_SAFE("Move cash from the reception to the safe", Table.SAFE, "IN"),
		SAFE_TO_BANK("Take cash from the safe to the Association's bank account", Table.SAFE, "BANK"),
		RECEPTION_TO_BANK("Take cash from the reception straight to the Association's bank account", Table.RECEPTION, "BANK"),
		SAFE_PAYMENT("Pay something from the safe (not an expense)", Table.SAFE, "OUT"),
		DEPOSIT("Put money into the reception (e.g. change float)", Table.RECEPTION, "DEPOSIT"),
		WITHDRAWAL("Take money out of the reception (not an expense)", Table.RECEPTION, "WITHDRAWAL");

		public final String label;
		final Table table;
		final String type;

		Action(String label, Table table, String type)
		{
			this.label = label;
			this.table = table;
			this.type = type;
		}

		public String getLabel()
		{
			return label;
		}
	}

	/** What the admin records on a bank account; TRANSFER goes from that account to the other one. */
	public enum BankAction
	{
		IN("Other money in (subsidy, refund, interest…)"),
		OUT("Other payment (bank fees… not an expense)"),
		TRANSFER("Transfer to the other account");

		public final String label;

		BankAction(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}
	}

	public static class ReserveRuleException extends RuntimeException
	{
		public ReserveRuleException(String message)
		{
			super(message);
		}
	}

	private final MovementRepository movements;
	private final BankRepository bank;
	private final FinanceRepository finance;
	private final AuditService audit;
	private final TransactionTemplate tx;
	private final Clock clock;

	public ReserveService(MovementRepository movements, BankRepository bank, FinanceRepository finance, AuditService audit,
						  TransactionTemplate tx, Clock clock)
	{
		this.movements = movements;
		this.bank = bank;
		this.finance = finance;
		this.audit = audit;
		this.tx = tx;
		this.clock = clock;
	}

	/** @return a warning to show when the source balance went below zero, else null */
	public String record(Action action, String dateText, String amountText, String noteText, Actor actor)
	{
		requireAdmin(actor);
		LocalDate date = date(dateText);
		double amount = amount(amountText);
		String note = note(noteText);
		tx.executeWithoutResult(s -> {
			long id = movements.insert(action.table, date, action.type, amount, note);
			audit.record(actor, "RESERVE_" + action.name(), action.table.name().toLowerCase(Locale.ROOT) + "_movement", id,
					date + " " + money(amount) + (note == null ? "" : " — " + note));
		});
		return negativeWarning(action);
	}

	public enum Place
	{
		RECEPTION, SAFE
	}

	/**
	 * Sets a balance to the amount actually counted, by recording the difference as a
	 * correction. Reception: DEPOSIT / WITHDRAWAL (as the desktop app did). Safe: ADJUST_IN /
	 * ADJUST_OUT, which unlike IN do not take money away from the reception.
	 */
	public String setBalance(Place place, String countedText, String noteText, Actor actor)
	{
		requireAdmin(actor);
		Double counted = Money.parse(countedText);
		if (counted == null || counted < 0 || counted > Money.MAX_AMOUNT)
		{
			throw new ReserveRuleException("Enter the amount counted, 0 or more.");
		}
		LocalDate today = LocalDate.now(clock);
		double current = place == Place.RECEPTION
				? finance.receptionBalance(today).balance()
				: finance.safeBalance().balance();
		double diff = Math.round((counted - current) * 100) / 100.0;
		if (Math.abs(diff) < 0.005)
		{
			return "The " + place.name().toLowerCase(Locale.ROOT) + " balance already matches " + money(counted) + ". Nothing recorded.";
		}
		String extra = note(noteText);
		String note = "Count correction: counted " + money(counted) + ", system said " + money(current)
				+ (extra == null ? "" : " — " + extra);
		Table table = place == Place.RECEPTION ? Table.RECEPTION : Table.SAFE;
		String type = place == Place.RECEPTION
				? (diff > 0 ? "DEPOSIT" : "WITHDRAWAL")
				: (diff > 0 ? "ADJUST_IN" : "ADJUST_OUT");
		tx.executeWithoutResult(s -> {
			long id = movements.insert(table, today, type, Math.abs(diff), note.length() > 200 ? note.substring(0, 200) : note);
			audit.record(actor, "RESERVE_SET_BALANCE", table.name().toLowerCase(Locale.ROOT) + "_movement", id, note);
		});
		return null;
	}

	public void delete(Table table, long id, Actor actor)
	{
		requireAdmin(actor);
		StoredMovement m = movements.find(table, id).orElseThrow(() -> new ReserveRuleException("Movement not found."));
		tx.executeWithoutResult(s -> {
			movements.delete(table, id);
			audit.record(actor, "RESERVE_DELETE", table.name().toLowerCase(Locale.ROOT) + "_movement", id,
					m.date() + " " + m.type() + " " + money(m.amount()) + (m.note() == null ? "" : " — " + m.note()));
		});
	}

	/* ======================= bank accounts ======================= */

	/** @return a warning when the account went below zero, else null */
	public String recordBank(BankAccount account, BankAction action, String dateText, String amountText, String noteText, Actor actor)
	{
		requireAdmin(actor);
		LocalDate date = date(dateText);
		double amount = amount(amountText);
		String note = note(noteText);
		tx.executeWithoutResult(s -> {
			if (action == BankAction.TRANSFER)
			{
				long out = bank.insert(account, date, "TRANSFER_OUT", amount, note);
				long in = bank.insert(account.other(), date, "TRANSFER_IN", amount, note);
				audit.record(actor, "BANK_TRANSFER", "bank_movement", out,
						account.name() + " -> " + account.other().name() + " " + date + " " + money(amount) + " (#" + out + ", #" + in + ")"
								+ (note == null ? "" : " — " + note));
			}
			else
			{
				long id = bank.insert(account, date, action.name(), amount, note);
				audit.record(actor, "BANK_" + action.name(), "bank_movement", id,
						account.name() + " " + date + " " + money(amount) + (note == null ? "" : " — " + note));
			}
		});
		if (action != BankAction.IN && finance.bankBalance(account).balance() < 0)
		{
			return "Recorded — but the " + account.label + " is now negative here. Check the amount, or correct it to the statement.";
		}
		return null;
	}

	/** Sets an account to the balance on the bank statement, recording the difference as a correction. */
	public String setBankBalance(BankAccount account, String statementText, String noteText, Actor actor)
	{
		requireAdmin(actor);
		Double statement = Money.parse(statementText);
		if (statement == null || statement < -Money.MAX_AMOUNT || statement > Money.MAX_AMOUNT)
		{
			throw new ReserveRuleException("Enter the balance shown on the statement.");
		}
		double current = finance.bankBalance(account).balance();
		double diff = Math.round((statement - current) * 100) / 100.0;
		if (Math.abs(diff) < 0.005)
		{
			return "The " + account.label + " already shows " + money(statement) + ". Nothing recorded.";
		}
		String extra = note(noteText);
		String note = "Statement correction: statement " + money(statement) + ", system said " + money(current)
				+ (extra == null ? "" : " — " + extra);
		String type = diff > 0 ? "ADJUST_IN" : "ADJUST_OUT";
		tx.executeWithoutResult(s -> {
			long id = bank.insert(account, LocalDate.now(clock), type, Math.abs(diff), note.length() > 200 ? note.substring(0, 200) : note);
			audit.record(actor, "BANK_SET_BALANCE", "bank_movement", id, account.name() + " " + note);
		});
		return null;
	}

	public void deleteBank(long id, Actor actor)
	{
		requireAdmin(actor);
		BankRepository.StoredMovement m = bank.find(id).orElseThrow(() -> new ReserveRuleException("Movement not found."));
		tx.executeWithoutResult(s -> {
			bank.delete(id);
			audit.record(actor, "BANK_DELETE", "bank_movement", id,
					m.account().name() + " " + m.date() + " " + m.type() + " " + money(m.amount()) + (m.note() == null ? "" : " — " + m.note()));
		});
	}

	/* ---------------------------------------------------------------- */

	private String negativeWarning(Action action)
	{
		LocalDate today = LocalDate.now(clock);
		boolean fromReception = action == Action.RECEPTION_TO_SAFE || action == Action.RECEPTION_TO_BANK || action == Action.WITHDRAWAL;
		boolean fromSafe = action == Action.SAFE_TO_BANK || action == Action.SAFE_PAYMENT;
		if (fromReception && finance.receptionBalance(today).balance() < 0)
		{
			return "Recorded — but the reception balance is now negative. Check the amount, or correct the balance with a count.";
		}
		if (fromSafe && finance.safeBalance().balance() < 0)
		{
			return "Recorded — but the safe balance is now negative. Check the amount, or correct the safe with a count.";
		}
		return null;
	}

	private LocalDate date(String text)
	{
		LocalDate date;
		try
		{
			date = LocalDate.parse(text == null ? "" : text.trim());
		}
		catch (DateTimeParseException e)
		{
			throw new ReserveRuleException("Choose the date.");
		}
		if (date.isAfter(LocalDate.now(clock)))
		{
			throw new ReserveRuleException("The date cannot be in the future.");
		}
		if (date.isBefore(LocalDate.of(2020, 1, 1)))
		{
			throw new ReserveRuleException("Check the year of the date.");
		}
		return date;
	}

	private static double amount(String text)
	{
		Double amount = Money.parse(text);
		if (!Money.isValidAmount(amount))
		{
			throw new ReserveRuleException("Enter an amount greater than 0.");
		}
		return amount;
	}

	private static String note(String text)
	{
		if (text == null || text.isBlank())
		{
			return null;
		}
		String n = text.trim().replaceAll("\\s+", " ");
		return n.length() > 150 ? n.substring(0, 150) : n;
	}

	private static void requireAdmin(Actor actor)
	{
		if (!actor.isAdmin())
		{
			throw new ReserveRuleException("Only an admin can record cash movements.");
		}
	}

	private static String money(double v)
	{
		return String.format(Locale.US, "%,.2f", v);
	}
}
