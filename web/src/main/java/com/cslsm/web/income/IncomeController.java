package com.cslsm.web.income;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Income for any period. Admins only: receptionists see today's and this week's totals on
 * the Today page and nothing beyond.
 */
@Controller
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class IncomeController
{
	private final IncomeService income;
	private final Clock clock;

	public IncomeController(IncomeService income, Clock clock)
	{
		this.income = income;
		this.clock = clock;
	}

	@GetMapping("/income")
	public String income(@RequestParam(required = false) String unit, @RequestParam(required = false) String date,
						 @RequestParam(required = false) String from, @RequestParam(required = false) String to,
						 @RequestParam(defaultValue = "false") boolean compare, Model model)
	{
		LocalDate today = LocalDate.now(clock);
		IncomePeriod period = IncomePeriod.resolve(unit, date, from, to, today);
		model.addAttribute("view", income.build(period, compare, today));
		model.addAttribute("units", IncomePeriod.Unit.values());
		model.addAttribute("today", today);
		return "income";
	}
}
