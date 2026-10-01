package com.cslsm.web.pages;

import com.cslsm.web.activities.ActivityAnalysisService;
import com.cslsm.web.activities.ActivityMath;
import com.cslsm.web.finance.DashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Clock;
import java.time.LocalDate;

@Controller
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class DashboardController
{
	private final DashboardService dashboard;
	private final ActivityAnalysisService activities;
	private final Clock clock;

	public DashboardController(DashboardService dashboard, ActivityAnalysisService activities, Clock clock)
	{
		this.dashboard = dashboard;
		this.activities = activities;
		this.clock = clock;
	}

	@GetMapping("/dashboard")
	public String dashboard(Model model)
	{
		LocalDate today = LocalDate.now(clock);
		model.addAttribute("view", dashboard.build(today));
		model.addAttribute("activities", activities.analyse(today.withDayOfMonth(1), today, ActivityMath.Spread.REVENUE));
		return "dashboard";
	}
}
