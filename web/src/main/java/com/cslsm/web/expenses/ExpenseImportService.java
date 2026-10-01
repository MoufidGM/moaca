package com.cslsm.web.expenses;

import com.cslsm.web.expenses.ExpenseModels.ExpenseDraft;
import com.cslsm.web.expenses.ExpenseModels.ExpenseRuleException;
import com.cslsm.web.expenses.ExpenseModels.Option;
import com.cslsm.web.expenses.ExpenseSheetParser.SheetException;
import com.cslsm.web.expenses.ExpenseSheetParser.SheetRow;
import com.cslsm.web.support.Actor;
import com.cslsm.web.support.AuditService;
import com.cslsm.web.support.FileStorage;
import com.cslsm.web.support.FileTypes;
import com.cslsm.web.support.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Import of an expense sheet in two steps: preview (every row checked, nothing saved) then
 * confirm (only rows without errors are saved, all in one transaction). An admin's rows are
 * saved approved; the reception's and the restaurant manager's wait for approval, like their
 * manual entries, and follow the same limits (categories, where the money came from). The
 * file itself is kept on disk and recorded, so admins can find it later by date.
 */
@Service
public class ExpenseImportService
{
	static final long MAX_BYTES = 5L * 1024 * 1024;
	/** How far back a sheet uploaded by the reception or the manager may go. */
	static final int STAFF_MAX_DAYS_BACK = 60;
	public static final List<String> UNITS = List.of("CENTER", "RESTAURANT", "SALON");

	private final ExpenseOptionRepository options;
	private final ExpenseRepository expenses;
	private final ExpenseImportRepository imports;
	private final FileStorage storage;
	private final AuditService audit;
	private final TransactionTemplate tx;
	private final Clock clock;

	public ExpenseImportService(ExpenseOptionRepository options, ExpenseRepository expenses, ExpenseImportRepository imports,
								FileStorage storage, AuditService audit, TransactionTemplate tx, Clock clock)
	{
		this.options = options;
		this.expenses = expenses;
		this.imports = imports;
		this.storage = storage;
		this.audit = audit;
		this.tx = tx;
		this.clock = clock;
	}

	/** Which units a user may upload sheets for. */
	public static List<String> unitsFor(Actor actor)
	{
		return actor.isRestaurant() ? List.of("RESTAURANT") : UNITS;
	}

	public static String unitLabel(String unit)
	{
		return switch (unit)
		{
			case "RESTAURANT" -> "Tiki Taka";
			case "SALON" -> "the Salon";
			default -> "the center";
		};
	}

	/** One checked row. Serializable: the preview waits in the session until confirmed. */
	public record PreviewRow(int rowNumber, LocalDate date, String category, String description, Double amount,
							 String payment, String paidFrom, String enteredBy, String approvedBy, String activity,
							 List<String> errors, List<String> notes) implements Serializable
	{
		public boolean ok()
		{
			return errors.isEmpty();
		}
	}

	public record Preview(String token, String fileName, String unit, byte[] bytes, List<PreviewRow> rows) implements Serializable
	{
		public long okCount()
		{
			return rows.stream().filter(PreviewRow::ok).count();
		}

		public long errorCount()
		{
			return rows.size() - okCount();
		}

		public double okTotal()
		{
			return rows.stream().filter(PreviewRow::ok).mapToDouble(r -> r.amount() == null ? 0 : r.amount()).sum();
		}
	}

	public Preview preview(String originalName, byte[] bytes, String unitText, Actor actor)
	{
		String unit = unitText == null ? "CENTER" : unitText.trim().toUpperCase(Locale.ROOT);
		if (!unitsFor(actor).contains(unit))
		{
			throw new ExpenseRuleException("Say whether the sheet is the center's, Tiki Taka's or the Salon's.");
		}
		if (bytes.length == 0 || bytes.length > MAX_BYTES)
		{
			throw new ExpenseRuleException("Upload an Excel file under 5 MB.");
		}
		if (FileTypes.sniffWorkbookExtension(bytes) == null)
		{
			throw new ExpenseRuleException("This is not an Excel file (.xlsx).");
		}
		List<SheetRow> sheetRows;
		try
		{
			sheetRows = ExpenseSheetParser.parse(new ByteArrayInputStream(bytes));
		}
		catch (SheetException e)
		{
			throw new ExpenseRuleException(e.getMessage());
		}
		if (sheetRows.isEmpty())
		{
			throw new ExpenseRuleException("No expense rows found. Columns: Date | Category | Description | Amount | …");
		}

		LocalDate today = LocalDate.now(clock);
		Set<String> seenInFile = new HashSet<>();
		List<PreviewRow> checked = new ArrayList<>();
		for (SheetRow r : sheetRows)
		{
			checked.add(check(r, unit, actor, today, seenInFile));
		}
		return new Preview(UUID.randomUUID().toString(), FileTypes.safeName(originalName), unit, bytes, checked);
	}

	private PreviewRow check(SheetRow r, String unit, Actor actor, LocalDate today, Set<String> seenInFile)
	{
		List<String> errors = new ArrayList<>();
		List<String> notes = new ArrayList<>();

		if (r.date == null)
		{
			errors.add("Unreadable date “" + (r.dateText == null ? "" : r.dateText) + "”.");
		}
		else if (r.date.isAfter(today))
		{
			errors.add("Date " + r.date + " is in the future — check the year.");
		}
		else if (!actor.isAdmin() && r.date.isBefore(today.minusDays(STAFF_MAX_DAYS_BACK)))
		{
			errors.add("Date " + r.date + " is more than " + STAFF_MAX_DAYS_BACK + " days back — an admin has to enter it.");
		}

		String category = null;
		Optional<Option> cat = options.findCategory(r.category);
		if (cat.isEmpty() && ExpenseSheetParser.categoryAlias(r.category) != null)
		{
			cat = options.findCategory(ExpenseSheetParser.categoryAlias(r.category));
		}
		if (cat.isPresent())
		{
			category = cat.get().name();
			if (!category.equalsIgnoreCase(r.category == null ? "" : r.category.trim()))
			{
				notes.add("Category “" + r.category + "” recorded as " + category + ".");
			}
			if (!actor.isAdmin() && cat.get().adminOnly())
			{
				errors.add("“" + category + "” is entered by an admin (salaries, bills, rent…).");
			}
		}
		else
		{
			errors.add(r.category == null ? "Missing category." : "Unknown category “" + r.category + "”.");
		}

		// Tiki Taka's and the Salon's sheets are their own activity's; the center's names it per row
		String activity;
		if (!"CENTER".equals(unit))
		{
			activity = "RESTAURANT".equals(unit) ? ExpenseService.RESTAURANT_ACTIVITY : "Salon";
			if (options.findActivity(activity).isEmpty())
			{
				errors.add("The “" + activity + "” activity is switched off. Ask an admin.");
			}
			else if (r.activity != null && !activity.equalsIgnoreCase(r.activity.trim()))
			{
				notes.add("Activity “" + r.activity + "” recorded as " + activity + " (this is " + unitLabel(unit) + "'s sheet).");
			}
		}
		else
		{
			Optional<Option> act = options.findActivity(r.activity);
			if (act.isEmpty() && ExpenseSheetParser.activityAlias(r.activity) != null)
			{
				act = options.findActivity(ExpenseSheetParser.activityAlias(r.activity));
			}
			if (act.isPresent())
			{
				activity = act.get().name();
				if (r.activity != null && !activity.equalsIgnoreCase(r.activity.trim()))
				{
					notes.add("Activity “" + r.activity + "” recorded as " + activity + ".");
				}
			}
			else
			{
				activity = "General";
				if (r.activity != null)
				{
					notes.add("Activity “" + r.activity + "” is not in the list — recorded as General.");
				}
			}
		}

		if (!Money.isValidAmount(r.amount))
		{
			errors.add("Amount must be greater than 0.");
		}

		String description = r.description == null ? null : (r.description.length() > 300 ? r.description.substring(0, 300) : r.description);
		if (description == null)
		{
			errors.add("Missing description.");
		}

		String payment = r.payment == null ? "CASH" : r.payment.trim().toUpperCase(Locale.ROOT);
		if (payment.equals("ESPECES") || payment.equals("ESPÈCES"))
		{
			payment = "CASH";
		}
		if (payment.equals("CHEQUE") || payment.equals("CHÈQUE"))
		{
			payment = "CHEQUE";
		}
		if (payment.equals("VIREMENT"))
		{
			payment = "TRANSFER";
		}
		if (payment.equals("CARTE"))
		{
			payment = "CARD";
		}
		if (!ExpenseModels.PAYMENT_METHODS.contains(payment))
		{
			errors.add("Unknown payment “" + r.payment + "” (CASH, CARD, CHEQUE or TRANSFER).");
		}

		String from = ExpenseSheetParser.normalize(r.fromStorage);
		String paidFrom = switch (from)
		{
			case "", "yes", "oui", "y", "o", "true", "1", "reception", "caisse" -> "RECEPTION";
			case "no", "non", "n", "false", "0", "bank", "banque" -> "BANK";
			case "safe", "coffre", "coffre-fort", "coffre fort" -> "SAFE";
			default -> null;
		};
		if (paidFrom == null)
		{
			errors.add("Column I must be YES (till), NO (bank) or SAFE.");
		}
		else if ("RESTAURANT".equals(unit))
		{
			// The restaurant pays from its own till and its own account
			paidFrom = "RECEPTION".equals(paidFrom) ? "RESTAURANT" : "BANK".equals(paidFrom) ? "RESTAURANT_BANK" : paidFrom;
		}
		if (!actor.isAdmin())
		{
			String till = "RESTAURANT".equals(unit) ? "RESTAURANT" : "RECEPTION";
			if (!till.equals(paidFrom))
			{
				notes.add("Recorded as paid from the till: the safe and the bank are an admin's.");
				paidFrom = till;
			}
		}

		if (r.date != null && category != null && Money.isValidAmount(r.amount))
		{
			expenses.similar(r.date, category, r.amount, null)
					.ifPresent(id -> notes.add("Possible duplicate of expense #" + id + " (same day, category and amount)."));
			String key = r.date + "|" + category + "|" + r.amount + "|" + ExpenseSheetParser.normalize(description);
			if (!seenInFile.add(key))
			{
				notes.add("Same as an earlier row in this file.");
			}
		}

		return new PreviewRow(r.rowNumber, r.date, category, description, r.amount, payment, paidFrom,
				r.enteredBy == null ? actor.displayName() : trim(r.enteredBy, 80),
				r.approvedBy == null ? actor.displayName() : trim(r.approvedBy, 80),
				activity, List.copyOf(errors), List.copyOf(notes));
	}

	/** Saves the rows without errors and keeps the file. Returns how many rows were saved. */
	public int confirm(Preview preview, Actor actor)
	{
		if (!unitsFor(actor).contains(preview.unit()))
		{
			throw new ExpenseRuleException("This sheet is not yours to import.");
		}
		List<PreviewRow> ok = preview.rows().stream().filter(PreviewRow::ok).toList();
		if (ok.isEmpty())
		{
			throw new ExpenseRuleException("No valid rows to import.");
		}
		boolean admin = actor.isAdmin();
		String status = admin ? "APPROVED" : "PENDING";
		String approvedAt = admin ? Instant.now().truncatedTo(ChronoUnit.SECONDS).toString() : null;
		LocalDate from = ok.stream().map(PreviewRow::date).min(LocalDate::compareTo).orElse(null);
		LocalDate to = ok.stream().map(PreviewRow::date).max(LocalDate::compareTo).orElse(null);
		String stored;
		try
		{
			stored = storage.store("expense-sheets", preview.bytes(), FileTypes.sniffWorkbookExtension(preview.bytes()));
		}
		catch (IOException e)
		{
			throw new ExpenseRuleException("The server could not keep the file. Tell the administrator.");
		}
		try
		{
			tx.executeWithoutResult(s -> {
				for (PreviewRow r : ok)
				{
					expenses.insert(new ExpenseDraft(r.date(), r.category(), r.description(), r.amount(), r.payment(),
							r.activity(), r.paidFrom(), status, admin ? r.enteredBy() : actor.displayName(),
							admin ? r.approvedBy() : null, approvedAt, actor.id()));
				}
				long importId = imports.insert(preview.unit(), preview.fileName(), stored, FileTypes.sha256Hex(preview.bytes()),
						preview.bytes().length, ok.size(), (int) preview.errorCount(), preview.okTotal(), from, to, status, actor.username());
				audit.record(actor, "EXPENSE_IMPORT", "expense_import", importId, unitLabel(preview.unit()) + " " + preview.fileName()
						+ ": " + ok.size() + " rows " + status.toLowerCase(Locale.ROOT) + ", total "
						+ String.format(Locale.US, "%,.2f", preview.okTotal()) + ", skipped " + preview.errorCount());
			});
		}
		catch (RuntimeException e)
		{
			storage.deleteQuietly(stored);
			throw e;
		}
		return ok.size();
	}

	private static String trim(String s, int max)
	{
		return s.length() <= max ? s : s.substring(0, max);
	}
}
