package com.cslsm.web.restaurant;

import com.cslsm.web.finance.FinanceRepository;
import com.cslsm.web.reserves.MovementRepository;
import com.cslsm.web.reserves.MovementRepository.Table;
import com.cslsm.web.restaurant.RestaurantSalesRepository.Sale;
import com.cslsm.web.support.Actor;
import com.cslsm.web.support.AuditService;
import com.cslsm.web.support.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

/**
 * Tiki Taka. The restaurant manager (or an admin) records each day's sales; the cash goes
 * into the restaurant's own till, from which its expenses are paid and cash is moved to the
 * safe. Card sales go straight to the bank.
 */
@Service
public class RestaurantService
{
	/** How far back the manager may still correct a day. */
	static final int MANAGER_MAX_DAYS_BACK = 7;

	public static class RestaurantException extends RuntimeException
	{
		public RestaurantException(String message)
		{
			super(message);
		}
	}

	/** Movements an admin records from the restaurant page. */
	public enum Action
	{
		RESTAURANT_TO_SAFE("Move cash from the restaurant to the safe", Table.SAFE, "IN"),
		RESTAURANT_TO_BANK("Take restaurant cash straight to the bank", Table.RECEPTION, "BANK"),
		DEPOSIT("Put money into the restaurant till (e.g. change float)", Table.RECEPTION, "DEPOSIT"),
		WITHDRAWAL("Take money out of the restaurant till (not an expense)", Table.RECEPTION, "WITHDRAWAL");

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

	private final RestaurantSalesRepository sales;
	private final MovementRepository movements;
	private final FinanceRepository finance;
	private final AuditService audit;
	private final TransactionTemplate tx;
	private final Clock clock;

	public RestaurantService(RestaurantSalesRepository sales, MovementRepository movements, FinanceRepository finance,
							 AuditService audit, TransactionTemplate tx, Clock clock)
	{
		this.sales = sales;
		this.movements = movements;
		this.finance = finance;
		this.audit = audit;
		this.tx = tx;
		this.clock = clock;
	}

	public void recordSales(String dateText, String cashText, String cardText, String coversText, String noteText, Actor actor)
	{
		if (!actor.isAdmin() && !actor.isRestaurant())
		{
			throw new RestaurantException("Only the restaurant manager or an admin can record sales.");
		}
		LocalDate today = LocalDate.now(clock);
		LocalDate date;
		try
		{
			date = LocalDate.parse(dateText == null ? "" : dateText.trim());
		}
		catch (DateTimeParseException e)
		{
			throw new RestaurantException("Choose the day.");
		}
		if (date.isAfter(today))
		{
			throw new RestaurantException("The day cannot be in the future.");
		}
		if (!actor.isAdmin() && date.isBefore(today.minusDays(MANAGER_MAX_DAYS_BACK)))
		{
			throw new RestaurantException("Sales can be corrected up to " + MANAGER_MAX_DAYS_BACK + " days back. Ask an admin for older days.");
		}
		double cash = amount(cashText, "cash");
		double card = amount(cardText, "card");
		if (cash == 0 && card == 0)
		{
			throw new RestaurantException("Enter the cash and card sales (0 is fine for one of them, not both). Delete the day instead if it was closed.");
		}
		Integer covers = null;
		if (coversText != null && !coversText.isBlank())
		{
			try
			{
				covers = Integer.parseInt(coversText.trim());
				if (covers < 0 || covers > 5000)
				{
					throw new NumberFormatException();
				}
			}
			catch (NumberFormatException e)
			{
				throw new RestaurantException("Covers must be a whole number.");
			}
		}
		String note = noteText == null || noteText.isBlank() ? null : noteText.trim().replaceAll("\\s+", " ");
		if (note != null && note.length() > 200)
		{
			note = note.substring(0, 200);
		}
		Optional<Sale> before = sales.find(date);
		final Integer coversFinal = covers;
		final String noteFinal = note;
		tx.executeWithoutResult(s -> {
			sales.upsert(date, cash, card, coversFinal, noteFinal, actor.displayName());
			audit.record(actor, before.isPresent() ? "RESTAURANT_SALES_UPDATE" : "RESTAURANT_SALES", "restaurant_sales", date,
					(before.map(b -> "was cash " + money(b.cash()) + " card " + money(b.card()) + " -> ").orElse(""))
							+ "cash " + money(cash) + " card " + money(card) + (coversFinal == null ? "" : ", " + coversFinal + " covers"));
		});
	}

	public void deleteSales(String dateText, Actor actor)
	{
		if (!actor.isAdmin())
		{
			throw new RestaurantException("Only an admin can delete a day's sales.");
		}
		LocalDate date;
		try
		{
			date = LocalDate.parse(dateText);
		}
		catch (DateTimeParseException | NullPointerException e)
		{
			throw new RestaurantException("Unknown day.");
		}
		Optional<Sale> before = sales.find(date);
		tx.executeWithoutResult(s -> {
			if (sales.delete(date))
			{
				audit.record(actor, "RESTAURANT_SALES_DELETE", "restaurant_sales", date,
						before.map(b -> "cash " + money(b.cash()) + " card " + money(b.card())).orElse(null));
			}
		});
	}

	/** @return a warning when the till went negative, else null */
	public String record(Action action, String dateText, String amountText, String noteText, Actor actor)
	{
		if (!actor.isAdmin())
		{
			throw new RestaurantException("Only an admin can record cash movements.");
		}
		LocalDate today = LocalDate.now(clock);
		LocalDate date;
		try
		{
			date = LocalDate.parse(dateText == null ? "" : dateText.trim());
		}
		catch (DateTimeParseException e)
		{
			throw new RestaurantException("Choose the date.");
		}
		if (date.isAfter(today))
		{
			throw new RestaurantException("The date cannot be in the future.");
		}
		double amount = amount(amountText, "amount");
		if (amount <= 0)
		{
			throw new RestaurantException("Enter an amount greater than 0.");
		}
		String note = noteText == null || noteText.isBlank() ? null : noteText.trim();
		tx.executeWithoutResult(s -> {
			long id = movements.insert(action.table, date, action.type, amount, note, "RESTAURANT");
			audit.record(actor, "RESTAURANT_" + action.name(), action.table.name().toLowerCase(Locale.ROOT) + "_movement", id,
					date + " " + money(amount) + (note == null ? "" : " — " + note));
		});
		boolean out = action != Action.DEPOSIT;
		if (out && finance.restaurantBalance(today).balance() < 0)
		{
			return "Recorded — but the restaurant till is now negative. Check the amount, or the sales entered.";
		}
		return null;
	}

	private static double amount(String text, String what)
	{
		if (text == null || text.isBlank())
		{
			return 0;
		}
		Double v = Money.parse(text);
		if (v == null || v < 0 || v > Money.MAX_AMOUNT)
		{
			throw new RestaurantException("Check the " + what + " amount.");
		}
		return v;
	}

	private static String money(double v)
	{
		return String.format(Locale.US, "%,.2f", v);
	}
}
