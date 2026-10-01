package com.cslsm.web.pages;

import com.cslsm.web.dailylog.DailyLogRepository;
import com.cslsm.web.dailylog.DailyLogRepository.ImportRow;
import com.cslsm.web.expenses.ExpenseImportRepository;
import com.cslsm.web.expenses.ExpenseImportRepository.ImportedSheet;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Every file uploaded — the three daily logs and the expense sheets — by month, newest first. Admins only. */
@Controller
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class FilesController
{
	/** One line of the list, whatever kind of file it was. */
	public record FileRow(LocalDate date, String kind, String unit, String name, String result, String by, String when, String link)
	{
	}

	private final DailyLogRepository dailyLogs;
	private final ExpenseImportRepository expenseSheets;
	private final Clock clock;

	public FilesController(DailyLogRepository dailyLogs, ExpenseImportRepository expenseSheets, Clock clock)
	{
		this.dailyLogs = dailyLogs;
		this.expenseSheets = expenseSheets;
		this.clock = clock;
	}

	@GetMapping("/files")
	public String files(@RequestParam(required = false) String month, @RequestParam(required = false) String kind, Model model)
	{
		YearMonth ym;
		try
		{
			ym = month == null || month.isBlank() ? YearMonth.from(LocalDate.now(clock)) : YearMonth.parse(month.trim());
		}
		catch (DateTimeParseException e)
		{
			ym = YearMonth.from(LocalDate.now(clock));
		}
		LocalDate from = ym.atDay(1);
		LocalDate to = ym.atEndOfMonth();
		List<FileRow> rows = new ArrayList<>();
		if (kind == null || kind.isBlank() || "daily".equals(kind))
		{
			for (ImportRow i : dailyLogs.storedBetween(from, to))
			{
				rows.add(new FileRow(i.logDate(), "Daily log", i.unitLabel(), i.originalName(),
						("REPLACED".equals(i.status()) ? "Replaced" : "Imported") + (i.hasWarnings() ? ", " + i.warnings().size() + " warning(s)" : ""),
						i.uploadedBy(), i.uploadedLocal(), "/daily-logs/" + i.id() + "/file"));
			}
		}
		if (kind == null || kind.isBlank() || "expenses".equals(kind))
		{
			for (ImportedSheet s : expenseSheets.between(from, to))
			{
				rows.add(new FileRow(s.date(), "Expense sheet", s.unitLabel(), s.originalName(),
						s.rowsSaved() + " row(s) " + s.status().toLowerCase(Locale.ROOT) + ", " + String.format(Locale.US, "%,.0f", s.total())
								+ (s.rowsSkipped() > 0 ? ", " + s.rowsSkipped() + " skipped" : "")
								+ (s.fromDate() != null && !s.fromDate().equals(s.toDate()) ? " (" + s.fromDate() + " → " + s.toDate() + ")" : ""),
						s.uploadedBy(), s.uploadedAt().substring(0, 16).replace('T', ' ') + " UTC", "/expenses/import/" + s.id() + "/file"));
			}
		}
		rows.sort(Comparator.comparing(FileRow::date).reversed().thenComparing(FileRow::kind));
		model.addAttribute("rows", rows);
		model.addAttribute("month", ym.toString());
		model.addAttribute("monthLabel", ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear());
		model.addAttribute("prevMonth", ym.minusMonths(1).toString());
		model.addAttribute("nextMonth", ym.plusMonths(1).toString());
		model.addAttribute("kind", kind == null ? "" : kind);
		return "files";
	}
}
