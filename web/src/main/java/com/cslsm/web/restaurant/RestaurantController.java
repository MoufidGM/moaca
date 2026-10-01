package com.cslsm.web.restaurant;

import com.cslsm.web.expenses.ExpenseModels.ExpenseRow;
import com.cslsm.web.expenses.ExpenseModels.ExpenseSearch;
import com.cslsm.web.expenses.ExpenseRepository;
import com.cslsm.web.finance.FinanceRepository;
import com.cslsm.web.restaurant.RestaurantSalesRepository.Sale;
import com.cslsm.web.restaurant.RestaurantService.RestaurantException;
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

@Controller
@RequestMapping("/restaurant")
@PreAuthorize("hasAnyRole('RESTAURANT_MANAGER', 'ADMIN', 'SUPER_ADMIN')")
public class RestaurantController
{
	private final RestaurantSalesRepository sales;
	private final RestaurantService service;
	private final FinanceRepository finance;
	private final ExpenseRepository expenses;
	private final CurrentActor currentActor;
	private final Clock clock;

	public RestaurantController(RestaurantSalesRepository sales, RestaurantService service, FinanceRepository finance,
								ExpenseRepository expenses, CurrentActor currentActor, Clock clock)
	{
		this.sales = sales;
		this.service = service;
		this.finance = finance;
		this.expenses = expenses;
		this.currentActor = currentActor;
		this.clock = clock;
	}

	@GetMapping
	public String page(@RequestParam(required = false) String month, Model model)
	{
		Actor actor = currentActor.require();
		LocalDate today = LocalDate.now(clock);
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
		double cash = rows.stream().mapToDouble(Sale::cash).sum();
		double card = rows.stream().mapToDouble(Sale::card).sum();
		List<ExpenseRow> monthExpenses = expenses.search(new ExpenseSearch(from, to, null, null, "Tiki Taka", null, null, null, false))
				.stream().filter(e -> !e.isRejected()).toList();
		double spent = monthExpenses.stream().mapToDouble(ExpenseRow::amount).sum();

		model.addAttribute("today", today.toString());
		model.addAttribute("minDate", actor.isAdmin() ? "2020-01-01" : today.minusDays(RestaurantService.MANAGER_MAX_DAYS_BACK).toString());
		model.addAttribute("todaySale", sales.find(today).orElse(null));
		model.addAttribute("month", ym.toString());
		model.addAttribute("monthLabel", ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear());
		model.addAttribute("prevMonth", ym.minusMonths(1).toString());
		model.addAttribute("nextMonth", ym.plusMonths(1).toString());
		model.addAttribute("sales", rows);
		model.addAttribute("cashTotal", cash);
		model.addAttribute("cardTotal", card);
		model.addAttribute("daysEntered", rows.size());
		model.addAttribute("expensesTotal", spent);
		model.addAttribute("expenseCount", monthExpenses.size());
		model.addAttribute("isAdmin", actor.isAdmin());
		if (actor.isAdmin())
		{
			model.addAttribute("till", finance.restaurantBalance(today));
			model.addAttribute("bank", finance.bankBalance(com.cslsm.web.finance.FinanceModels.BankAccount.RESTAURANT));
			model.addAttribute("movements", finance.recentRestaurantMovements(30));
			model.addAttribute("actions", RestaurantService.Action.values());
		}
		return "restaurant";
	}

	@PostMapping("/sales")
	public String sales(@RequestParam String date, @RequestParam(defaultValue = "") String cash,
						@RequestParam(defaultValue = "") String card, @RequestParam(defaultValue = "") String covers,
						@RequestParam(defaultValue = "") String note, RedirectAttributes redirect)
	{
		service.recordSales(date, cash, card, covers, note, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Sales recorded for " + date + ".");
		return "redirect:/restaurant?month=" + date.substring(0, Math.min(7, date.length()));
	}

	@PostMapping("/sales/delete")
	@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
	public String deleteSales(@RequestParam String date, RedirectAttributes redirect)
	{
		service.deleteSales(date, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Sales of " + date + " deleted.");
		return "redirect:/restaurant?month=" + date.substring(0, Math.min(7, date.length()));
	}

	@PostMapping("/movements")
	@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
	public String movement(@RequestParam String action, @RequestParam(defaultValue = "") String date,
						   @RequestParam(defaultValue = "") String amount, @RequestParam(defaultValue = "") String note,
						   RedirectAttributes redirect)
	{
		RestaurantService.Action a;
		try
		{
			a = RestaurantService.Action.valueOf(action);
		}
		catch (IllegalArgumentException e)
		{
			throw new RestaurantException("Choose what you are recording.");
		}
		String warning = service.record(a, date, amount, note, currentActor.require());
		redirect.addFlashAttribute(warning == null ? "flashOk" : "flashError", warning == null ? "Recorded." : warning);
		return "redirect:/restaurant";
	}

	@ExceptionHandler(RestaurantException.class)
	public String rule(RestaurantException e, RedirectAttributes redirect)
	{
		redirect.addFlashAttribute("flashError", e.getMessage());
		return "redirect:/restaurant";
	}
}
