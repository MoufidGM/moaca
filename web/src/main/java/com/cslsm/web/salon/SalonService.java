package com.cslsm.web.salon;

import com.cslsm.web.salon.SalonSalesRepository.Sale;
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
 * The Salon. The reception (or an admin) records each day's sales; the cash goes into the
 * reception till like the daily log's, card sales go straight to the bank. Its expenses are
 * ordinary expenses on the "Salon" activity, so the activity analysis shows its profit.
 */
@Service
public class SalonService
{
	/** How far back the reception may still correct a day. */
	static final int RECEPTION_MAX_DAYS_BACK = 7;

	public static class SalonException extends RuntimeException
	{
		public SalonException(String message)
		{
			super(message);
		}
	}

	private final SalonSalesRepository sales;
	private final AuditService audit;
	private final TransactionTemplate tx;
	private final Clock clock;

	public SalonService(SalonSalesRepository sales, AuditService audit, TransactionTemplate tx, Clock clock)
	{
		this.sales = sales;
		this.audit = audit;
		this.tx = tx;
		this.clock = clock;
	}

	public void recordSales(String dateText, String cashText, String cardText, String clientsText, String noteText, Actor actor)
	{
		if (!actor.isAdmin() && !actor.isReception())
		{
			throw new SalonException("Only the reception or an admin can record the Salon's sales.");
		}
		LocalDate today = LocalDate.now(clock);
		LocalDate date;
		try
		{
			date = LocalDate.parse(dateText == null ? "" : dateText.trim());
		}
		catch (DateTimeParseException e)
		{
			throw new SalonException("Choose the day.");
		}
		if (date.isAfter(today))
		{
			throw new SalonException("The day cannot be in the future.");
		}
		if (!actor.isAdmin() && date.isBefore(today.minusDays(RECEPTION_MAX_DAYS_BACK)))
		{
			throw new SalonException("Sales can be corrected up to " + RECEPTION_MAX_DAYS_BACK + " days back. Ask an admin for older days.");
		}
		double cash = amount(cashText, "cash");
		double card = amount(cardText, "card");
		if (cash == 0 && card == 0)
		{
			throw new SalonException("Enter the cash and card sales (0 is fine for one of them, not both). Delete the day instead if it was closed.");
		}
		Integer clients = null;
		if (clientsText != null && !clientsText.isBlank())
		{
			try
			{
				clients = Integer.parseInt(clientsText.trim());
				if (clients < 0 || clients > 5000)
				{
					throw new NumberFormatException();
				}
			}
			catch (NumberFormatException e)
			{
				throw new SalonException("Clients must be a whole number.");
			}
		}
		String note = noteText == null || noteText.isBlank() ? null : noteText.trim().replaceAll("\\s+", " ");
		if (note != null && note.length() > 200)
		{
			note = note.substring(0, 200);
		}
		Optional<Sale> before = sales.find(date);
		final Integer clientsFinal = clients;
		final String noteFinal = note;
		tx.executeWithoutResult(s -> {
			sales.upsert(date, cash, card, clientsFinal, noteFinal, actor.displayName());
			audit.record(actor, before.isPresent() ? "SALON_SALES_UPDATE" : "SALON_SALES", "salon_sales", date,
					(before.map(b -> "was cash " + money(b.cash()) + " card " + money(b.card()) + " -> ").orElse(""))
							+ "cash " + money(cash) + " card " + money(card) + (clientsFinal == null ? "" : ", " + clientsFinal + " clients"));
		});
	}

	public void deleteSales(String dateText, Actor actor)
	{
		if (!actor.isAdmin())
		{
			throw new SalonException("Only an admin can delete a day's sales.");
		}
		LocalDate date;
		try
		{
			date = LocalDate.parse(dateText);
		}
		catch (DateTimeParseException | NullPointerException e)
		{
			throw new SalonException("Unknown day.");
		}
		Optional<Sale> before = sales.find(date);
		tx.executeWithoutResult(s -> {
			if (sales.delete(date))
			{
				audit.record(actor, "SALON_SALES_DELETE", "salon_sales", date,
						before.map(b -> "cash " + money(b.cash()) + " card " + money(b.card())).orElse(null));
			}
		});
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
			throw new SalonException("Check the " + what + " amount.");
		}
		return v;
	}

	private static String money(double v)
	{
		return String.format(Locale.US, "%,.2f", v);
	}
}
