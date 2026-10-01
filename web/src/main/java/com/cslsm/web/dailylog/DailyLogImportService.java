package com.cslsm.web.dailylog;

import com.cslsm.web.dailylog.DailyLogParser.DailyLogException;
import com.cslsm.web.dailylog.DailyLogParser.ParsedDay;
import com.cslsm.web.restaurant.RestaurantSalesRepository;
import java.time.temporal.ChronoUnit;
import java.time.Instant;
import com.cslsm.web.support.Money;
import com.cslsm.web.expenses.ExpenseModels.Option;
import com.cslsm.web.expenses.ExpenseModels.ExpenseDraft;
import com.cslsm.web.expenses.ExpenseSheetParser;
import com.cslsm.web.expenses.ExpenseService;
import com.cslsm.web.expenses.ExpenseOptionRepository;
import com.cslsm.web.expenses.ExpenseRepository;
import com.cslsm.web.restaurant.FamilyRepository;
import com.cslsm.web.salon.SalonSalesRepository;
import com.cslsm.web.support.Actor;
import com.cslsm.web.support.AuditService;
import com.cslsm.web.support.FileStorage;
import com.cslsm.web.support.FileTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Imports one uploaded daily log: the center's (DL-…), or Tiki Taka's (TT-…) and the Salon's
 * (SA-…), told apart by the file name.
 *
 * Refused (nothing saved, reason shown):
 *   bad file name, date in the future, not an Excel file, no figures at the expected cells,
 *   day already imported (receptionist: always; admin: unless "replace" is ticked).
 *
 * Imported with warnings (shown on the dashboard until an admin marks them checked):
 *   cash + card + cheque ≠ total   (held on all 437 historical days — a mismatch means a wrong file)
 *   departments ≠ total            (held on 436 of 437 days)
 *   negative figures
 *   byte-identical to the file of another day (a copied and renamed file)
 *   total more than 3× or less than a fifth of a typical day
 */
@Service
public class DailyLogImportService
{
	static final long MAX_BYTES = 10L * 1024 * 1024;
	private static final double TOLERANCE = 1.0;

	private final DailyLogParser parser;
	private final List<UnitLogParser> unitParsers;
	private final DailyLogRepository repo;
	private final RestaurantSalesRepository restaurantSales;
	private final SalonSalesRepository salonSales;
	private final FamilyRepository family;
	private final ExpenseRepository expenseRepo;
	private final ExpenseOptionRepository options;
	private final FileStorage storage;
	private final AuditService audit;
	private final TransactionTemplate tx;
	private final Clock clock;

	public DailyLogImportService(DailyLogParser parser, List<UnitLogParser> unitParsers, DailyLogRepository repo,
								 RestaurantSalesRepository restaurantSales, SalonSalesRepository salonSales, FamilyRepository family,
								 ExpenseRepository expenseRepo, ExpenseOptionRepository options, FileStorage storage,
								 AuditService audit, TransactionTemplate tx, Clock clock)
	{
		this.parser = parser;
		this.unitParsers = unitParsers;
		this.repo = repo;
		this.restaurantSales = restaurantSales;
		this.salonSales = salonSales;
		this.family = family;
		this.expenseRepo = expenseRepo;
		this.options = options;
		this.storage = storage;
		this.audit = audit;
		this.tx = tx;
		this.clock = clock;
	}

	/** Which unit a file is for, from its prefix: DL (center), TT, SA. */
	public static String unitOf(String name, List<UnitLogParser> parsers)
	{
		for (UnitLogParser p : parsers)
		{
			if (p.owns(name))
			{
				return p.layout().unit;
			}
		}
		return "CENTER";
	}

	/** The reception uploads all three units' files; the restaurant manager the restaurant's only. */
	static boolean mayUpload(String unit, Actor actor)
	{
		if (actor.isAdmin() || actor.isReception())
		{
			return true;
		}
		return "RESTAURANT".equals(unit) && actor.isRestaurant();
	}

	public record Outcome(String fileName, LocalDate date, boolean imported, boolean replaced,
						  Double total, String message, List<String> warnings)
	{
	}

	public Outcome importFile(MultipartFile file, Actor actor, boolean replace)
	{
		String name = FileTypes.safeName(file.getOriginalFilename());
		byte[] bytes;
		try
		{
			bytes = file.getBytes();
		}
		catch (IOException e)
		{
			return refuse(name, null, null, "The upload was interrupted. Try again.", actor);
		}
		return importBytes(name, bytes, actor, replace);
	}

	Outcome importBytes(String name, byte[] bytes, Actor actor, boolean replace)
	{
		if (bytes.length == 0)
		{
			return refuse(name, null, null, "The file is empty.", actor);
		}
		if (bytes.length > MAX_BYTES)
		{
			return refuse(name, null, null, "The file is larger than 10 MB — a daily log is normally under 100 KB.", actor);
		}

		for (UnitLogParser unitParser : unitParsers)
		{
			if (unitParser.owns(name))
			{
				return importUnitBytes(unitParser, name, bytes, actor, replace);
			}
		}
		if (!mayUpload("CENTER", actor))
		{
			return refuse(name, null, null, "Your account uploads Tiki Taka's files (TT-…), not the center's.", actor);
		}
		LocalDate date;
		try
		{
			date = DailyLogParser.dateFromFileName(name);
		}
		catch (DailyLogException e)
		{
			return refuse(name, null, null, e.getMessage(), actor);
		}

		LocalDate today = LocalDate.now(clock);
		if (date.isAfter(today))
		{
			return refuse(name, date, null, "The date " + date + " is in the future. Check the file name.", actor);
		}

		String extension = FileTypes.sniffWorkbookExtension(bytes);
		if (extension == null)
		{
			return refuse(name, date, null, "This is not an Excel file. Upload the .xlsx exported for the day.", actor);
		}

		String sha = FileTypes.sha256Hex(bytes);
		Optional<Double> existing = repo.existingTotal(date);
		if (existing.isPresent())
		{
			if (!actor.isAdmin())
			{
				return refuse(name, date, sha, "This day is already imported. If the file was corrected, ask an admin to replace it.", actor);
			}
			if (!replace)
			{
				return refuse(name, date, sha, "This day is already imported. Tick “Replace days already imported” to overwrite it.", actor);
			}
		}

		ParsedDay day;
		try
		{
			day = parser.parse(new ByteArrayInputStream(bytes), date);
		}
		catch (DailyLogException e)
		{
			return refuse(name, date, sha, e.getMessage(), actor);
		}
		if (day.isEmpty())
		{
			return refuse(name, date, sha, "No figures were found in the expected cells. Is this the daily log template?", actor);
		}

		List<String> warnings = checks(day, sha);

		String stored;
		try
		{
			stored = storage.store("daily-logs", bytes, extension);
		}
		catch (IOException e)
		{
			return refuse(name, date, sha, "The server could not save the file. Tell the administrator.", actor);
		}

		boolean replacing = existing.isPresent();
		String status = replacing ? "REPLACED" : "IMPORTED";
		try
		{
			tx.executeWithoutResult(s -> {
				repo.upsertDay(day, storage.absolute(stored).toString());
				long importId = repo.insertImport(date, name, stored, sha, (long) bytes.length, status,
						day.totalTtc, warnings, null, actor.username());
				audit.record(actor, replacing ? "DAILY_LOG_REPLACE" : "DAILY_LOG_IMPORT", "daily_summary", date,
						"file=" + name + ", total=" + money(day.totalTtc)
								+ (replacing ? ", previous total=" + money(existing.get()) : "")
								+ ", import #" + importId
								+ (warnings.isEmpty() ? "" : ", warnings=" + warnings.size()));
			});
		}
		catch (RuntimeException e)
		{
			storage.deleteQuietly(stored);
			throw e;
		}

		String drinks = day.drinks != 0 ? " + drinks " + money(day.drinks) : "";
		String message = replacing
				? "Replaced " + date + " (total was " + money(existing.get()) + ", now " + money(day.totalTtc) + drinks + ")."
				: "Imported " + date + ": " + money(day.totalTtc) + drinks + ".";
		return new Outcome(name, date, true, replacing, day.totalTtc, message, warnings);
	}

	/* ======================= Tiki Taka's and the Salon's files ======================= */

	private Outcome importUnitBytes(UnitLogParser unitParser, String name, byte[] bytes, Actor actor, boolean replace)
	{
		String unit = unitParser.layout().unit;
		String label = "RESTAURANT".equals(unit) ? "Tiki Taka" : "the Salon";
		if (!mayUpload(unit, actor))
		{
			return refuse(name, null, null, "Your account cannot upload " + label + "'s files.", actor, unit);
		}
		LocalDate date;
		try
		{
			date = unitParser.dateFromFileName(name);
		}
		catch (DailyLogException e)
		{
			return refuse(name, null, null, e.getMessage(), actor, unit);
		}
		LocalDate today = LocalDate.now(clock);
		if (date.isAfter(today))
		{
			return refuse(name, date, null, "The date " + date + " is in the future. Check the file name.", actor, unit);
		}
		String extension = FileTypes.sniffWorkbookExtension(bytes);
		if (extension == null)
		{
			return refuse(name, date, null, "This is not an Excel file. Upload the .xlsx made from the template.", actor, unit);
		}
		String sha = FileTypes.sha256Hex(bytes);
		Optional<Double> existing = "RESTAURANT".equals(unit)
				? restaurantSales.find(date).map(RestaurantSalesRepository.Sale::total)
				: salonSales.find(date).map(SalonSalesRepository.Sale::total);
		if (existing.isPresent())
		{
			if (!actor.isAdmin())
			{
				return refuse(name, date, sha, "This day is already recorded. If the file was corrected, ask an admin to replace it.", actor, unit);
			}
			if (!replace)
			{
				return refuse(name, date, sha, "This day is already recorded. Tick “Replace days already imported” to overwrite it.", actor, unit);
			}
		}
		UnitLogParser.ParsedUnitDay day;
		try
		{
			day = unitParser.parse(new ByteArrayInputStream(bytes), date);
		}
		catch (DailyLogException e)
		{
			return refuse(name, date, sha, e.getMessage(), actor, unit);
		}
		if (day.isEmpty())
		{
			return refuse(name, date, sha, "No figures were found in the expected cells. Is this " + label + "'s template?", actor, unit);
		}
		List<String> warnings = new ArrayList<>();
		if (day.card() + day.onAccount() > day.total() + TOLERANCE)
		{
			warnings.add("Card " + money(day.card()) + (day.onAccount() > 0 ? " and family " + money(day.onAccount()) : "")
					+ " are more than the day's total " + money(day.total()) + ".");
		}
		if (day.cashLeft() < -TOLERANCE)
		{
			warnings.add("The till paid out " + money(day.tillExpenses()) + " with only " + money(day.cash()) + " of cash sales.");
		}
		if (day.total() < 0 || day.card() < 0 || day.onAccount() < 0)
		{
			warnings.add("The file contains a negative amount.");
		}
		List<String> lineProblems = lineProblems(day);
		if (!lineProblems.isEmpty())
		{
			return refuse(name, date, sha, String.join(" ", lineProblems), actor, unit);
		}
		repo.otherDateWithSameContent(sha, date, unit).ifPresent(other ->
				warnings.add("This file is identical to the one imported for " + other + ". Was it copied and renamed?"));

		String stored;
		try
		{
			stored = storage.store("daily-logs", bytes, extension);
		}
		catch (IOException e)
		{
			return refuse(name, date, sha, "The server could not save the file. Tell the administrator.", actor, unit);
		}
		boolean replacing = existing.isPresent();
		String status = replacing ? "REPLACED" : "IMPORTED";
		String note = day.note() == null ? "file " + name : day.note();
		List<Long> previous = repo.importIdsFor(unit, date);
		try
		{
			tx.executeWithoutResult(s -> {
				if ("RESTAURANT".equals(unit))
				{
					restaurantSales.upsert(date, day.cash(), day.card(), day.onAccount(), day.people(), note, actor.displayName());
				}
				else
				{
					salonSales.upsert(date, day.cash(), day.card(), day.people(), note, actor.displayName());
				}
				long importId = repo.insertImport(date, name, stored, sha, (long) bytes.length, status, day.total(), warnings, null,
						actor.username(), unit);
				// A replaced file replaces what the earlier one created
				int oldMeals = family.deleteFromImports(previous);
				int oldExpenses = expenseRepo.deleteFromImports(previous);
				for (UnitLogParser.FamilyLine f : day.family())
				{
					family.insert(date, f.member(), f.amount(), f.note(), importId);
				}
				double expensesTotal = 0;
				for (UnitLogParser.ExpenseLine e : day.expenses())
				{
					long id = expenseRepo.insert(new ExpenseDraft(date, category(e.category()), e.object(), e.amount(), "CASH",
							ExpenseService.RESTAURANT_ACTIVITY, "RESTAURANT", actor.isAdmin() ? "APPROVED" : "PENDING",
							actor.displayName(), actor.isAdmin() ? actor.displayName() : null,
							actor.isAdmin() ? Instant.now().truncatedTo(ChronoUnit.SECONDS).toString() : null, actor.id()));
					expenseRepo.markSource(id, importId);
					expensesTotal += e.amount();
				}
				audit.record(actor, (replacing ? "DAILY_LOG_REPLACE" : "DAILY_LOG_IMPORT"), unit.toLowerCase(Locale.ROOT) + "_sales", date,
						"file=" + name + ", total=" + money(day.total()) + ", card=" + money(day.card())
								+ (day.onAccount() > 0 ? ", family=" + money(day.onAccount()) + " (" + day.family().size() + " meals)" : "")
								+ (day.expenses().isEmpty() ? "" : ", till expenses=" + money(expensesTotal) + " (" + day.expenses().size() + ")")
								+ (replacing ? ", previous total=" + money(existing.get()) + ", removed " + oldMeals + " meal(s) and " + oldExpenses + " expense(s)" : "")
								+ ", import #" + importId + (warnings.isEmpty() ? "" : ", warnings=" + warnings.size()));
			});
		}
		catch (RuntimeException e)
		{
			storage.deleteQuietly(stored);
			throw e;
		}
		String message = (replacing ? "Replaced " : "Imported ") + label + " " + date + ": " + money(day.total())
				+ " (card " + money(day.card()) + ", cash " + money(day.cash())
				+ (day.onAccount() > 0 ? ", family " + money(day.onAccount()) : "")
				+ (day.expenses().isEmpty() ? "" : ", " + day.expenses().size() + " expense(s) from the till" + (actor.isAdmin() ? "" : " waiting for approval"))
				+ ")" + (replacing ? " — was " + money(existing.get()) : "") + ".";
		return new Outcome(name, date, true, replacing, day.total(), message, warnings);
	}

	/** Family and expense lines that cannot be saved as they are; the file is refused so it gets fixed, not half-imported. */
	private static List<String> lineProblems(UnitLogParser.ParsedUnitDay day)
	{
		List<String> out = new ArrayList<>();
		for (UnitLogParser.FamilyLine f : day.family())
		{
			if (f.member() == null)
			{
				out.add("Row " + f.rowNumber() + ": a family line needs a name.");
			}
			else if (f.amount() <= 0 || f.amount() > Money.MAX_AMOUNT)
			{
				out.add("Row " + f.rowNumber() + ": check the amount for " + f.member() + ".");
			}
		}
		for (UnitLogParser.ExpenseLine e : day.expenses())
		{
			if (e.object() == null)
			{
				out.add("Row " + e.rowNumber() + ": an expense line needs an object.");
			}
			else if (e.amount() <= 0 || e.amount() > Money.MAX_AMOUNT)
			{
				out.add("Row " + e.rowNumber() + ": check the amount for " + e.object() + ".");
			}
		}
		return out;
	}

	/** The category typed on the file, by name or alias; a restaurant's purchases otherwise. */
	private String category(String text)
	{
		if (text != null)
		{
			Optional<Option> found = options.findCategory(text);
			if (found.isEmpty() && ExpenseSheetParser.categoryAlias(text) != null)
			{
				found = options.findCategory(ExpenseSheetParser.categoryAlias(text));
			}
			if (found.isPresent())
			{
				return found.get().name();
			}
		}
		return options.findCategory("Food & drinks").map(Option::name).orElse("Other");
	}

	private Outcome refuse(String name, LocalDate date, String sha, String reason, Actor actor, String unit)
	{
		repo.insertImport(date, name, null, sha, null, "REJECTED", null, List.of(), reason, actor.username(), unit);
		return new Outcome(name, date, false, false, null, reason, List.of());
	}

	private List<String> checks(ParsedDay day, String sha)
	{
		List<String> warnings = new ArrayList<>();
		if (Math.abs(day.paymentsTotal() - day.totalTtc) > TOLERANCE)
		{
			warnings.add("Cash + card + cheque = " + money(day.paymentsTotal()) + " but the total is " + money(day.totalTtc) + ".");
		}
		if (Math.abs(day.departmentsTotal() - day.totalTtc) > TOLERANCE)
		{
			warnings.add("The departments add up to " + money(day.departmentsTotal()) + " but the total is " + money(day.totalTtc) + ".");
		}
		double[] values = {day.terrain, day.padel, day.gym, day.park, day.miniGolf, day.pingPong, day.academy,
				day.taekwondo, day.shoes, day.totalTtc, day.drinks, day.cash, day.card, day.cheque};
		for (double v : values)
		{
			if (v < 0)
			{
				warnings.add("The file contains a negative amount (" + money(v) + ").");
				break;
			}
		}
		repo.otherDateWithSameContent(sha, day.date).ifPresent(other ->
				warnings.add("This file is identical to the one imported for " + other + ". Was it copied and renamed?"));

		List<Double> recent = new ArrayList<>(repo.recentTotals(day.date, 60));
		if (recent.size() >= 10)
		{
			Collections.sort(recent);
			double median = recent.get(recent.size() / 2);
			if (day.totalTtc > median * 3)
			{
				warnings.add("The total " + money(day.totalTtc) + " is more than 3× a typical day (" + money(median) + ").");
			}
			else if (day.totalTtc < median / 5)
			{
				warnings.add("The total " + money(day.totalTtc) + " is less than a fifth of a typical day (" + money(median) + ").");
			}
		}
		return warnings;
	}

	private Outcome refuse(String name, LocalDate date, String sha, String reason, Actor actor)
	{
		repo.insertImport(date, name, null, sha, null, "REJECTED", null, List.of(), reason, actor.username());
		return new Outcome(name, date, false, false, null, reason, List.of());
	}

	static String money(double v)
	{
		return String.format(Locale.US, "%,.0f", v);
	}
}
