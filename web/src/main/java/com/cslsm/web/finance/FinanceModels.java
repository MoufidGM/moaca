package com.cslsm.web.finance;

import java.time.LocalDate;
import java.util.List;

/** Small immutable views passed from the finance queries to the pages. */
public final class FinanceModels
{
	private FinanceModels()
	{
	}

	/** An income department (including drinks) and its column in daily_summary. */
	public record Activity(String name, String column)
	{
	}

	public record NamedAmount(String name, double amount)
	{
	}

	/** One calendar day; total is null when no log file was imported for it. */
	public record DayRow(LocalDate date, Double total)
	{
		public boolean missing()
		{
			return total == null;
		}
	}

	public record DaySummary(LocalDate date, double total, double cash, double card, double cheque,
							 List<NamedAmount> activities)
	{
	}

	/** table is "RECEPTION" or "SAFE"; id identifies the row for deletion. */
	public record Movement(long id, String table, LocalDate date, String label, double amount, boolean incoming, String note)
	{
		public double signedAmount()
		{
			return incoming ? amount : -amount;
		}
	}

	public record FutureExpense(LocalDate date, String category, String description, double amount)
	{
	}

	/**
	 * Money at the reception: daily-log income + the Salon's cash + deposits − withdrawals − sent
	 * to bank − expenses paid from it − moved to the safe. Same formula as the desktop app, plus
	 * the Salon's cash, which the reception keeps in its till.
	 */
	public record ReceptionBalance(String scopeLabel, double income, double salonCash, double deposits, double withdrawals,
								   double sentToBank, double expensesPaid, double movedToSafe)
	{
		public double balance()
		{
			return income + salonCash + deposits - withdrawals - sentToBank - expensesPaid - movedToSafe;
		}
	}

	/**
	 * Money in the restaurant's till: cash sales + deposits − withdrawals − sent to bank
	 * − expenses paid from it − moved to the safe. Card sales go straight to Tiki Taka's bank account.
	 */
	public record RestaurantBalance(String scopeLabel, double cashSales, double cardSales, double deposits,
									double withdrawals, double sentToBank, double expensesPaid, double movedToSafe)
	{
		public double balance()
		{
			return cashSales + deposits - withdrawals - sentToBank - expensesPaid - movedToSafe;
		}
	}

	/** The two bank accounts. */
	public enum BankAccount
	{
		ASSOCIATION("Association bank account", "bank"), RESTAURANT("Tiki Taka bank account", "restaurant-bank");

		public final String label;
		/** The eye-toggle group on the pages. */
		public final String group;

		BankAccount(String label, String group)
		{
			this.label = label;
			this.group = group;
		}

		public String getLabel()
		{
			return label;
		}

		public String getGroup()
		{
			return group;
		}

		public BankAccount other()
		{
			return this == ASSOCIATION ? RESTAURANT : ASSOCIATION;
		}
	}

	/**
	 * A bank account, all time: card and cheque payments + cash brought from the tills and the
	 * safe + other money in (and transfers in) − other payments (and transfers out) − expenses
	 * paid from it, plus statement corrections. Never resets; correct it to the statement.
	 */
	public record BankBalance(BankAccount account, double cardAndCheque, double cashDeposited, double fromSafe,
							  double otherIn, double otherOut, double expensesPaid, double corrections)
	{
		public double balance()
		{
			return cardAndCheque + cashDeposited + fromSafe + otherIn - otherOut - expensesPaid + corrections;
		}
	}

	/**
	 * Physical safe: received from the reception (and upward count corrections) − sent to bank
	 * − other payments and downward corrections − expenses paid from the safe. Never resets.
	 */
	public record SafeBalance(double received, double sentToBank, double payments, double expensesPaid)
	{
		public double balance()
		{
			return received - sentToBank - payments - expensesPaid;
		}
	}
}
