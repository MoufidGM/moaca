package com.cslsm.web.activities;

import com.cslsm.web.activities.ActivityMath.Result;
import com.cslsm.web.activities.ActivityMath.Row;
import com.cslsm.web.activities.ActivityMath.Spread;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Revenue, costs and profit per activity. Admins only — this is the center's money. */
@Controller
@RequestMapping("/activities")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class ActivitiesController
{
	private final ActivityAnalysisService analysis;
	private final Clock clock;

	public ActivitiesController(ActivityAnalysisService analysis, Clock clock)
	{
		this.analysis = analysis;
		this.clock = clock;
	}

	@GetMapping
	public String overview(@RequestParam(required = false) String period, @RequestParam(required = false) String from,
						   @RequestParam(required = false) String to, @RequestParam(required = false) String spread,
						   Model model)
	{
		Period p = Period.resolve(period, from, to, LocalDate.now(clock));
		Spread s = spread(spread);
		Result result = analysis.analyse(p.from(), p.to(), s);

		List<ActivityBarsSvg.Bar> bars = new ArrayList<>();
		for (Row r : result.revenueRows)
		{
			if (r.revenue != 0 || r.getCosts() != 0)
			{
				bars.add(new ActivityBarsSvg.Bar(r.name, r.revenue, r.getCosts()));
			}
		}
		for (Row r : result.otherRows)
		{
			bars.add(new ActivityBarsSvg.Bar(r.name, 0, r.getCosts()));
		}

		common(model, p, s);
		model.addAttribute("result", result);
		model.addAttribute("chartSvg", ActivityBarsSvg.render(bars));
		return "activities";
	}

	@GetMapping("/detail")
	public String detail(@RequestParam String name, @RequestParam(required = false) String period,
						 @RequestParam(required = false) String from, @RequestParam(required = false) String to,
						 @RequestParam(required = false) String spread, Model model)
	{
		Period p = Period.resolve(period, from, to, LocalDate.now(clock));
		Spread s = spread(spread);
		ActivityAnalysisService.Detail detail = analysis.detail(name, p, s)
				.orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
		common(model, p, s);
		model.addAttribute("d", detail);
		return "activity-detail";
	}

	private static void common(Model model, Period p, Spread s)
	{
		model.addAttribute("period", p);
		model.addAttribute("spread", s.name());
		model.addAttribute("presets", Period.PRESETS);
	}

	private static Spread spread(String value)
	{
		try
		{
			return value == null ? Spread.REVENUE : Spread.valueOf(value);
		}
		catch (IllegalArgumentException e)
		{
			return Spread.REVENUE;
		}
	}
}
