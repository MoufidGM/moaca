package com.cslsm.web.activities;

import com.cslsm.web.activities.ActivityAnalysisRepository.UnknownName;
import com.cslsm.web.activities.ActivityRules.Def;
import com.cslsm.web.expenses.ExpenseModels.Option;
import com.cslsm.web.expenses.ExpenseOptionRepository;
import com.cslsm.web.expenses.ExpenseSheetParser;
import com.cslsm.web.support.Actor;
import com.cslsm.web.support.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Admin settings for the activity analysis: how each activity's costs count, new activities,
 * and merging old spellings found on expenses ("T-Foot", "Menage"…) into the list.
 */
@Service
public class ActivitySettingsService
{
	private static final Set<String> EDITABLE_RULES = Set.of("SHARED", "SEPARATE", "PART_OF");

	public static class SettingsException extends RuntimeException
	{
		public SettingsException(String message)
		{
			super(message);
		}
	}

	/** An activity name on expenses that is not in the list, and what to merge it into. */
	public record Unknown(String name, int expenses, double amount, String suggestion)
	{
		public boolean isBlank()
		{
			return name == null;
		}

		public String label()
		{
			return name == null ? "(no activity)" : name;
		}
	}

	private final ExpenseOptionRepository options;
	private final ActivityAnalysisRepository repo;
	private final ActivityAnalysisService analysis;
	private final CostKeyRepository keys;
	private final AuditService audit;
	private final TransactionTemplate tx;

	public ActivitySettingsService(ExpenseOptionRepository options, ActivityAnalysisRepository repo,
								   ActivityAnalysisService analysis, CostKeyRepository keys, AuditService audit, TransactionTemplate tx)
	{
		this.options = options;
		this.repo = repo;
		this.analysis = analysis;
		this.keys = keys;
		this.audit = audit;
		this.tx = tx;
	}

	public List<Unknown> unknownNames()
	{
		ActivityRules rules = analysis.rules();
		List<Unknown> out = new ArrayList<>();
		for (UnknownName n : repo.activityNamesInUse())
		{
			if (n.isBlank())
			{
				out.add(new Unknown(null, n.expenses(), n.amount(), "General"));
			}
			else if (rules.find(n.name()) == null)
			{
				String alias = ExpenseSheetParser.activityAlias(n.name());
				Def target = alias == null ? null : rules.find(alias);
				out.add(new Unknown(n.name(), n.expenses(), n.amount(), target == null ? null : target.name));
			}
		}
		return out;
	}

	/** Changes how an activity's costs count. Revenue activities keep their rule. */
	public void updateRule(long id, String rule, String partOf, boolean active, Actor actor)
	{
		requireAdmin(actor);
		Option o = options.findById(id).filter(x -> "ACTIVITY".equals(x.kind()))
				.orElseThrow(() -> new SettingsException("Activity not found."));
		String newRule = o.isRevenue() ? "REVENUE" : checkedRule(rule);
		String newPartOf = "PART_OF".equals(newRule) ? checkedParent(partOf, o.name()) : null;
		tx.executeWithoutResult(s -> {
			options.updateActivityRule(id, newRule, newPartOf, active);
			audit.record(actor, "ACTIVITY_RULE", "expense_option", id,
					o.name() + ": " + o.costRule() + (o.partOf() == null ? "" : " " + o.partOf()) + (o.active() ? "" : " (off)")
							+ " -> " + newRule + (newPartOf == null ? "" : " " + newPartOf) + (active ? "" : " (off)"));
		});
	}

	public void create(String nameText, String rule, String partOf, Actor actor)
	{
		requireAdmin(actor);
		String name = checkedNewName(nameText);
		String newRule = checkedRule(rule);
		String newPartOf = "PART_OF".equals(newRule) ? checkedParent(partOf, name) : null;
		tx.executeWithoutResult(s -> {
			options.createActivity(name, newRule, newPartOf);
			audit.record(actor, "ACTIVITY_CREATE", "expense_option", null, name + ": " + newRule + (newPartOf == null ? "" : " " + newPartOf));
		});
	}

	/**
	 * Renames an old spelling on every expense to an activity in the list. With keepAsNew, the
	 * spelling becomes a new activity instead (costs on their own line).
	 *
	 * @param from the spelling found on expenses; null for expenses with no activity
	 * @return the number of expenses changed
	 */
	public int merge(String from, String to, boolean keepAsNew, Actor actor)
	{
		requireAdmin(actor);
		ActivityRules rules = analysis.rules();
		if (from != null && rules.find(from) != null)
		{
			throw new SettingsException("“" + from + "” is already in the list.");
		}
		if (keepAsNew)
		{
			if (from == null)
			{
				throw new SettingsException("Expenses without an activity need to be merged into one.");
			}
			create(from, "SEPARATE", null, actor);
			return 0;
		}
		Def target = rules.find(to);
		if (target == null)
		{
			throw new SettingsException("Choose an activity from the list.");
		}
		Integer changed = tx.execute(s -> {
			int n = repo.renameActivity(from, target.name);
			audit.record(actor, "ACTIVITY_MERGE", "expense", null,
					(from == null ? "(no activity)" : "“" + from + "”") + " -> " + target.name + " on " + n + " expense(s)");
			return n;
		});
		return changed == null ? 0 : changed;
	}

	/** Merges every old spelling that has a suggestion. Returns the number of expenses changed. */
	public int mergeSuggested(Actor actor)
	{
		requireAdmin(actor);
		int total = 0;
		for (Unknown u : unknownNames())
		{
			if (u.suggestion() != null)
			{
				total += merge(u.name(), u.suggestion(), false, actor);
			}
		}
		return total;
	}

	/* ======================= allocation keys ======================= */

	/**
	 * Saves the percentages of a pool (UTILITIES or COMMON) per revenue activity. All lines
	 * empty clears the key, so the pool falls back to the spread chosen on the page.
	 */
	public void saveKey(String kind, List<String> activities, List<String> percents, Actor actor)
	{
		requireAdmin(actor);
		if (!CostKeyRepository.UTILITIES.equals(kind) && !CostKeyRepository.COMMON.equals(kind))
		{
			throw new SettingsException("Unknown key.");
		}
		ActivityRules rules = analysis.rules();
		java.util.Map<String, Double> key = new java.util.LinkedHashMap<>();
		double total = 0;
		int n = Math.max(activities == null ? 0 : activities.size(), percents == null ? 0 : percents.size());
		for (int i = 0; i < n; i++)
		{
			String name = activities != null && i < activities.size() && activities.get(i) != null ? activities.get(i).trim() : "";
			String text = percents != null && i < percents.size() && percents.get(i) != null ? percents.get(i).trim() : "";
			if (text.isEmpty())
			{
				continue;
			}
			Def d = rules.find(name);
			if (d == null || !d.isRevenue())
			{
				throw new SettingsException("Keys can only give percentages to activities that earn revenue.");
			}
			double pct;
			try
			{
				pct = Double.parseDouble(text.replace("%", "").replace(",", "."));
			}
			catch (NumberFormatException e)
			{
				throw new SettingsException("“" + text + "” is not a percentage.");
			}
			if (pct < 0 || pct > 100)
			{
				throw new SettingsException("Percentages are between 0 and 100.");
			}
			if (pct > 0)
			{
				key.put(d.name, Math.round(pct * 100) / 100.0);
				total += pct;
			}
		}
		if (!key.isEmpty() && Math.abs(total - 100) > 0.01)
		{
			throw new SettingsException("The percentages add up to " + String.format(Locale.ROOT, "%.2f", total).replaceAll("\\.?0+$", "") + "%, they must add up to 100% (or all be empty).");
		}
		java.util.Map<String, Double> before = keys.key(kind);
		tx.executeWithoutResult(s -> {
			keys.replace(kind, key);
			audit.record(actor, "COST_KEY", "cost_key", kind, before + " -> " + key);
		});
	}

	public java.util.Map<String, Double> key(String kind)
	{
		return keys.key(kind);
	}

	/* ---------------------------------------------------------------- */

	private static String checkedRule(String rule)
	{
		String r = rule == null ? "" : rule.trim().toUpperCase(Locale.ROOT);
		if (!EDITABLE_RULES.contains(r))
		{
			throw new SettingsException("Choose how its costs count.");
		}
		return r;
	}

	private String checkedParent(String partOf, String self)
	{
		Def parent = analysis.rules().find(partOf);
		if (parent == null || !parent.isRevenue())
		{
			throw new SettingsException("“Part of” must be an activity that earns revenue.");
		}
		if (parent.name.equalsIgnoreCase(self))
		{
			throw new SettingsException("An activity cannot be part of itself.");
		}
		return parent.name;
	}

	private String checkedNewName(String text)
	{
		String name = text == null ? "" : text.trim().replaceAll("\\s+", " ");
		if (!name.matches("[\\p{L}\\p{N} &'./+-]{2,40}"))
		{
			throw new SettingsException("Activity names are 2 to 40 letters, digits or spaces.");
		}
		if (options.findActivityIncludingInactive(name).isPresent())
		{
			throw new SettingsException("“" + name + "” already exists.");
		}
		return name;
	}

	private static void requireAdmin(Actor actor)
	{
		if (!actor.isAdmin())
		{
			throw new SettingsException("Only an admin can change activity settings.");
		}
	}
}
