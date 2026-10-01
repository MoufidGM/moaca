package com.cslsm.web.activities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How each activity's costs are counted (see V15). Built from expense_option on every
 * request, so a change on the settings page applies to the next report.
 */
public final class ActivityRules
{
	public enum Rule
	{
		REVENUE, SHARED, SEPARATE, PART_OF
	}

	/** Where an expense's cost ends up in the analysis. */
	public enum Kind
	{
		REVENUE, SHARED, SEPARATE, UNASSIGNED
	}

	public static final class Def
	{
		public final long id;
		public final String name;
		public final Rule rule;
		public final String incomeColumn;
		public final String partOf;
		public final boolean active;

		public Def(long id, String name, Rule rule, String incomeColumn, String partOf, boolean active)
		{
			this.id = id;
			this.name = name;
			this.rule = rule;
			this.incomeColumn = incomeColumn;
			this.partOf = partOf;
			this.active = active;
		}

		public long getId() { return id; }
		public String getName() { return name; }
		public Rule getRule() { return rule; }
		public String getIncomeColumn() { return incomeColumn; }
		public String getPartOf() { return partOf; }
		public boolean isActive() { return active; }
		public boolean isRevenue() { return rule == Rule.REVENUE; }
	}

	public static final class Target
	{
		public final Kind kind;
		public final String name;

		Target(Kind kind, String name)
		{
			this.kind = kind;
			this.name = name;
		}
	}

	private final Map<String, Def> byLowerName = new LinkedHashMap<>();
	private final List<Def> revenue = new ArrayList<>();

	/**
	 * @param defs              all activity options, in display order
	 * @param validIncomeColumn guard: only known daily_summary columns may be used in SQL
	 */
	public ActivityRules(List<Def> defs, java.util.function.Predicate<String> validIncomeColumn)
	{
		for (Def d : defs)
		{
			byLowerName.put(key(d.name), d);
			boolean validColumn = d.incomeColumn != null && (validIncomeColumn.test(d.incomeColumn)
					|| ActivityAnalysisRepository.isSalesTable(d.incomeColumn));
			if (d.rule == Rule.REVENUE && validColumn)
			{
				revenue.add(d);
			}
		}
	}

	public List<Def> all()
	{
		return new ArrayList<>(byLowerName.values());
	}

	/** Activities that earn revenue, with a valid daily-log column. */
	public List<Def> revenueActivities()
	{
		return Collections.unmodifiableList(revenue);
	}

	public Def find(String name)
	{
		return byLowerName.get(key(name));
	}

	/**
	 * Where a name written on an expense (or a split) belongs. Matching ignores case; names
	 * that are not in the list at all (old spellings) are UNASSIGNED until merged.
	 */
	public Target resolve(String raw)
	{
		if (raw == null || raw.trim().isEmpty())
		{
			return new Target(Kind.UNASSIGNED, "(none)");
		}
		Def d = find(raw);
		if (d == null)
		{
			return new Target(Kind.UNASSIGNED, raw.trim());
		}
		switch (d.rule)
		{
			case REVENUE:
				return revenue.contains(d) ? new Target(Kind.REVENUE, d.name) : new Target(Kind.SEPARATE, d.name);
			case SHARED:
				return new Target(Kind.SHARED, d.name);
			case PART_OF:
				Def parent = find(d.partOf);
				if (parent != null && revenue.contains(parent))
				{
					return new Target(Kind.REVENUE, parent.name);
				}
				return new Target(Kind.SEPARATE, d.name);
			default:
				return new Target(Kind.SEPARATE, d.name);
		}
	}

	/** Lower-case names whose costs count for an activity: itself plus anything "part of" it. */
	public Set<String> namesCountingAs(String activity)
	{
		Set<String> names = new HashSet<>();
		Def target = find(activity);
		if (target == null)
		{
			names.add(key(activity));
			return names;
		}
		names.add(key(target.name));
		for (Def d : byLowerName.values())
		{
			if (d.rule == Rule.PART_OF && d.partOf != null && key(d.partOf).equals(key(target.name)))
			{
				names.add(key(d.name));
			}
		}
		return names;
	}

	public Set<String> sharedNames()
	{
		Set<String> names = new HashSet<>();
		for (Def d : byLowerName.values())
		{
			if (d.rule == Rule.SHARED)
			{
				names.add(key(d.name));
			}
		}
		return names;
	}

	static String key(String name)
	{
		return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
	}
}
