package com.cslsm.web.activities;

import com.cslsm.web.activities.ActivitySettingsService.SettingsException;
import com.cslsm.web.support.CurrentActor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/activities/settings")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class ActivitySettingsController
{
	private final ActivitySettingsService settings;
	private final ActivityAnalysisService analysis;
	private final CurrentActor currentActor;

	public ActivitySettingsController(ActivitySettingsService settings, ActivityAnalysisService analysis, CurrentActor currentActor)
	{
		this.settings = settings;
		this.analysis = analysis;
		this.currentActor = currentActor;
	}

	@GetMapping
	public String page(Model model)
	{
		ActivityRules rules = analysis.rules();
		List<ActivitySettingsService.Unknown> unknown = settings.unknownNames();
		model.addAttribute("activities", rules.all());
		model.addAttribute("revenueActivities", rules.revenueActivities());
		model.addAttribute("activeActivities", rules.all().stream().filter(d -> d.active).toList());
		model.addAttribute("unknown", unknown);
		model.addAttribute("suggestedCount", unknown.stream().filter(u -> u.suggestion() != null).count());
		model.addAttribute("utilitiesKey", settings.key(CostKeyRepository.UTILITIES));
		model.addAttribute("commonKey", settings.key(CostKeyRepository.COMMON));
		return "activity-settings";
	}

	@PostMapping("/key")
	public String saveKey(@RequestParam String kind,
						  @RequestParam(name = "keyActivity", required = false) List<String> keyActivity,
						  @RequestParam(name = "keyPercent", required = false) List<String> keyPercent,
						  RedirectAttributes redirect)
	{
		settings.saveKey(kind, keyActivity, keyPercent, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Key saved. It applies to every period from now on.");
		return "redirect:/activities/settings";
	}

	@PostMapping("/{id}")
	public String update(@PathVariable long id, @RequestParam(defaultValue = "") String rule,
						 @RequestParam(defaultValue = "") String partOf,
						 @RequestParam(defaultValue = "false") boolean active, RedirectAttributes redirect)
	{
		settings.updateRule(id, rule, partOf, active, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Saved. The analysis uses the new setting right away, for every period.");
		return "redirect:/activities/settings";
	}

	@PostMapping("/new")
	public String create(@RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String rule,
						 @RequestParam(defaultValue = "") String partOf, RedirectAttributes redirect)
	{
		settings.create(name, rule, partOf, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Activity added.");
		return "redirect:/activities/settings";
	}

	@PostMapping("/merge")
	public String merge(@RequestParam(required = false) String from, @RequestParam(defaultValue = "false") boolean blank,
						@RequestParam(defaultValue = "") String to, RedirectAttributes redirect)
	{
		boolean keepAsNew = "__NEW__".equals(to);
		int n = settings.merge(blank ? null : from, to, keepAsNew, currentActor.require());
		redirect.addFlashAttribute("flashOk", keepAsNew
				? "“" + from + "” is now an activity of its own (costs on their own line)."
				: n + " expense(s) moved to " + to + ".");
		return "redirect:/activities/settings";
	}

	@PostMapping("/merge-suggested")
	public String mergeSuggested(RedirectAttributes redirect)
	{
		int n = settings.mergeSuggested(currentActor.require());
		redirect.addFlashAttribute("flashOk", n + " expense(s) moved to the suggested activities.");
		return "redirect:/activities/settings";
	}

	@ExceptionHandler(SettingsException.class)
	public String rule(SettingsException e, RedirectAttributes redirect)
	{
		redirect.addFlashAttribute("flashError", e.getMessage());
		return "redirect:/activities/settings";
	}
}
