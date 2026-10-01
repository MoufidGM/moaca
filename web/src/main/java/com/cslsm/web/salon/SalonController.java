package com.cslsm.web.salon;

import com.cslsm.web.expenses.ExpenseModels.ExpenseRow;
import com.cslsm.web.expenses.ExpenseModels.ExpenseSearch;
import com.cslsm.web.expenses.ExpenseRepository;
import com.cslsm.web.salon.SalonSalesRepository.Sale;
import com.cslsm.web.salon.SalonService.SalonException;
import com.cslsm.web.support.Actor;
import com.cslsm.web.support.CurrentActor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * The Salon's page (admins): the month, the Salon's expenses, the link to its full analysis,
 * and a form to type a day's totals when there is no file for it. The reception's way in is
 * the SA- daily file on Daily logs.
 */
@Controller
@RequestMapping("/salon")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class SalonController
{
	/** Days the reception sees on the page (it can correct the last 7). */
	private static final int RECEPTION_DAYS_SHOWN = 14;

	private final SalonSalesRepository sales;
	private final SalonService service;
	private final ExpenseRepository expenses;
	private final CurrentActor currentActor;
	private final Clock clock;

	public SalonController(SalonSalesRepository sales, SalonService service, ExpenseRepository expenses,
						   CurrentActor currentActor, Clock clock)
	{
		this.sales = sales;
		this.service = service;
		this.expenses = expenses;
		this.currentActor = currentActor;
		this.clock = clock;
	}

	@GetMapping
	public String page(@RequestParam(required = false) String month, Model model)
	{
		Actor actor = currentActor.require();
		LocalDate today = LocalDate.now(clock);
		model.addAttribute("today", today.toString());
		model.addAttribute("minDate", actor.isAdmin() ? "2020-01-01" : today.minusDays(SalonService.RECEPTION_MAX_DAYS_BACK).toString());
		model.addAttribute("todaySale", sales.find(today).orElse(null));
		model.addAttribute("isAdmin", actor.isAdmin());

		if (!actor.isAdmin())
		{
			model.addAttribute("sales", sales.between(today.minusDays(RECEPTION_DAYS_SHOWN - 1), today));
			return "salon";
		}
		YearMonth ym;
		try
		{
			ym = month == null || month.isBlank() ? YearMonth.from(today) : YearMonth.parse(month.trim());
		}
		catch (DateTimeParseException e)
		{
			ym = YearMonth.from(today);
		}
		LocalDate from = ym.atDay(1);
		LocalDate to = ym.atEndOfMonth().isAfter(today) ? today : ym.atEndOfMonth();
		List<Sale> rows = sales.between(from, to);
		List<ExpenseRow> monthExpenses = expenses.search(new ExpenseSearch(from, to, null, null, "Salon", null, null, null, false))
				.stream().filter(e -> !e.isRejected()).toList();
		model.addAttribute("month", ym.toString());
		model.addAttribute("monthLabel", ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear());
		model.addAttribute("prevMonth", ym.minusMonths(1).toString());
		model.addAttribute("nextMonth", ym.plusMonths(1).toString());
		model.addAttribute("sales", rows);
		model.addAttribute("cashTotal", rows.stream().mapToDouble(Sale::cash).sum());
		model.addAttribute("cardTotal", rows.stream().mapToDouble(Sale::card).sum());
		model.addAttribute("daysEntered", rows.size());
		model.addAttribute("expensesTotal", monthExpenses.stream().mapToDouble(ExpenseRow::amount).sum());
		model.addAttribute("expenseCount", monthExpenses.size());
		return "salon";
	}

	@PostMapping("/sales")
	public String sales(@RequestParam String date, @RequestParam(defaultValue = "") String cash,
						@RequestParam(defaultValue = "") String card, @RequestParam(defaultValue = "") String clients,
						@RequestParam(defaultValue = "") String note, RedirectAttributes redirect)
	{
		service.recordSales(date, cash, card, clients, note, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Salon sales recorded for " + date + ".");
		return "redirect:/salon?month=" + date.substring(0, Math.min(7, date.length()));
	}

	@PostMapping("/sales/delete")
	@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
	public String deleteSales(@RequestParam String date, RedirectAttributes redirect)
	{
		service.deleteSales(date, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Salon sales of " + date + " deleted.");
		return "redirect:/salon?month=" + date.substring(0, Math.min(7, date.length()));
	}

	@ExceptionHandler(SalonException.class)
	public String rule(SalonException e, RedirectAttributes redirect)
	{
		redirect.addFlashAttribute("flashError", e.getMessage());
		return "redirect:/salon";
	}
}
