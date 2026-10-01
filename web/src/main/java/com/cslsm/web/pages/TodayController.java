package com.cslsm.web.pages;

import com.cslsm.web.finance.TodayService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Clock;
import java.time.LocalDate;

@Controller
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class TodayController
{
	private final TodayService today;
	private final Clock clock;

	public TodayController(TodayService today, Clock clock)
	{
		this.today = today;
		this.clock = clock;
	}

	@GetMapping("/today")
	public String today(Model model)
	{
		TodayService.TodayView view = today.build(LocalDate.now(clock));
		model.addAttribute("view", view);
		model.addAttribute("day", view.latestDay().orElse(null));
		return "today";
	}
}
