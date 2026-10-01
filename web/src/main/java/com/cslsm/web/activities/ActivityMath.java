package com.cslsm.web.activities;

import com.cslsm.web.activities.ActivityRules.Kind;
import com.cslsm.web.activities.ActivityRules.Target;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns revenue per activity and costs per target into profit per activity, broken down by
 * cost heading.
 *
 * Revenue activities carry their direct costs plus a share of the common costs; activities on
 * their own line and unassigned names carry only their costs. Every dirham of income and cost
 * lands in exactly one row, so the rows always add up to the center's profit.
 *
 * Common costs come in two pools. Utilities (electricity, water, gas, heating, phone/internet)
 * follow the UTILITIES key; everything else follows the COMMON key. A key is a set of
 * percentages per activity set by an admin; without one, the pool is spread by the chosen
 * fallback (revenue, equal, or not at all).
 */
public final class ActivityMath
{
	/** Fallback for a pool with no key. */
	public enum Spread
	{
		REVENUE, EQUAL, NONE
	}

	/** The lines of an activity's cost breakdown. */
	public enum Heading
	{
		DIRECT_SALARIES("Salaries of its own staff"),
		SHARED_SALARIES("Share of other salaries"),
		MAINTENANCE("Maintenance and services"),
		PURCHASES("Purchases and equipment"),
		UTILITIES("Electricity, water, phone, internet"),
		COMMON("Share of common costs"),
		OTHER("Other");

		public final String label;

		Heading(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}
	}

	public static final class Row
	{
		public final String name;
		public final Kind kind;
		public final double revenue;
		public final double directCosts;
		public final double sharedCosts;
		public final int expenseCount;
		private final double totalRevenue;
		private final EnumMap<Heading, Double> headings;

		Row(String name, Kind kind, double revenue, double directCosts, double sharedCosts, int expenseCount,
			double totalRevenue, EnumMap<Heading, Double> headings)
		{
			this.name = name;
			this.kind = kind;
			this.revenue = revenue;
			this.directCosts = directCosts;
			this.sharedCosts = sharedCosts;
			this.expenseCount = expenseCount;
			this.totalRevenue = totalRevenue;
			this.headings = headings;
		}

		public String getName() { return name; }
		public Kind getKind() { return kind; }
		public double getRevenue() { return revenue; }
		public double getDirectCosts() { return directCosts; }
		public double getSharedCosts() { return sharedCosts; }
		public int getExpenseCount() { return expenseCount; }

		public double getCosts()
		{
			return directCosts + sharedCosts;
		}

		public double getProfit()
		{
			return revenue - directCosts - sharedCosts;
		}

		/** Profit as a share of revenue, or null when there is no revenue. */
		public Double getMargin()
		{
			return revenue > 0 ? getProfit() / revenue : null;
		}

		/** This activity's share of the center's revenue (0..1). */
		public double getRevenueShare()
		{
			return totalRevenue > 0 ? revenue / totalRevenue : 0;
		}

		public boolean isRevenueActivity()
		{
			return kind == Kind.REVENUE;
		}

		/** Costs by heading, in Heading order, zero lines left out. */
		public List<Map.Entry<Heading, Double>> getBreakdown()
		{
			List<Map.Entry<Heading, Double>> out = new ArrayList<>();
			for (Heading h : Heading.values())
			{
				double v = headings.getOrDefault(h, 0.0);
				if (Math.abs(v) >= 0.005)
				{
					out.add(Map.entry(h, v));
				}
			}
			return out;
		}

		public double heading(Heading h)
		{
			return headings.getOrDefault(h, 0.0);
		}
	}

	/** Costs collected per target before the pools are spread. */
	public static final class Costs
	{
		private static final class Bucket
		{
			final EnumMap<Heading, Double> headings = new EnumMap<>(Heading.class);
			double total;
			int count;

			void add(Heading h, double amount, int count)
			{
				headings.merge(h, amount, Double::sum);
				total += amount;
				this.count += count;
			}
		}

		private final Map<String, Bucket> direct = new LinkedHashMap<>();
		private final Map<String, Bucket> separate = new LinkedHashMap<>();
		private final Map<String, Bucket> unassigned = new LinkedHashMap<>();
		private double utilitiesPool;
		private double commonPool;
		private int sharedCount;

		/**
		 * @param heading  the category's heading: SALARIES, MAINTENANCE, PURCHASES, UTILITIES or OTHER
		 * @param percent  the part of the expense on this line; under 100 for a split expense
		 */
		public void add(Target t, String heading, double percent, double amount, int count)
		{
			switch (t.kind)
			{
				case REVENUE:
					bucket(direct, t.name).add(headingFor(heading, percent), amount, count);
					break;
				case SHARED:
					if ("UTILITIES".equals(heading))
					{
						utilitiesPool += amount;
					}
					else
					{
						commonPool += amount;
					}
					sharedCount += count;
					break;
				case SEPARATE:
					bucket(separate, t.name).add(headingFor(heading, percent), amount, count);
					break;
				default:
					bucket(unassigned, t.name).add(headingFor(heading, percent), amount, count);
			}
		}

		/** Older callers without headings: counted as OTHER. */
		public void add(Target t, double amount, int count)
		{
			add(t, "OTHER", 100, amount, count);
		}

		static Heading headingFor(String heading, double percent)
		{
			if (heading == null)
			{
				return Heading.OTHER;
			}
			switch (heading)
			{
				case "SALARIES":
					return percent < 100 ? Heading.SHARED_SALARIES : Heading.DIRECT_SALARIES;
				case "MAINTENANCE":
					return Heading.MAINTENANCE;
				case "PURCHASES":
					return Heading.PURCHASES;
				case "UTILITIES":
					return Heading.UTILITIES;
				default:
					return Heading.OTHER;
			}
		}

		public double shared()
		{
			return utilitiesPool + commonPool;
		}

		public double utilitiesPool()
		{
			return utilitiesPool;
		}

		public double commonPool()
		{
			return commonPool;
		}

		public double unassignedTotal()
		{
			return unassigned.values().stream().mapToDouble(b -> b.total).sum();
		}

		public int unassignedCount()
		{
			return unassigned.values().stream().mapToInt(b -> b.count).sum();
		}

		private static Bucket bucket(Map<String, Bucket> map, String name)
		{
			return map.computeIfAbsent(name, k -> new Bucket());
		}
	}

	public static final class Result
	{
		public final List<Row> revenueRows;
		public final List<Row> otherRows;
		public final double totalRevenue;
		public final double totalCosts;
		public final double sharedTotal;
		public final double unassignedTotal;
		public final int unassignedCount;
		public final Spread spread;
		public final boolean utilitiesKeyUsed;
		public final boolean commonKeyUsed;

		Result(List<Row> revenueRows, List<Row> otherRows, double totalRevenue, double totalCosts, double sharedTotal,
			   double unassignedTotal, int unassignedCount, Spread spread, boolean utilitiesKeyUsed, boolean commonKeyUsed)
		{
			this.revenueRows = revenueRows;
			this.otherRows = otherRows;
			this.totalRevenue = totalRevenue;
			this.totalCosts = totalCosts;
			this.sharedTotal = sharedTotal;
			this.unassignedTotal = unassignedTotal;
			this.unassignedCount = unassignedCount;
			this.spread = spread;
			this.utilitiesKeyUsed = utilitiesKeyUsed;
			this.commonKeyUsed = commonKeyUsed;
		}

		public List<Row> getRevenueRows() { return revenueRows; }
		public List<Row> getOtherRows() { return otherRows; }
		public double getTotalRevenue() { return totalRevenue; }
		public double getTotalCosts() { return totalCosts; }
		public double getSharedTotal() { return sharedTotal; }
		public double getUnassignedTotal() { return unassignedTotal; }
		public int getUnassignedCount() { return unassignedCount; }
		public Spread getSpread() { return spread; }
		public boolean isUtilitiesKeyUsed() { return utilitiesKeyUsed; }
		public boolean isCommonKeyUsed() { return commonKeyUsed; }

		public double getProfit()
		{
			return totalRevenue - totalCosts;
		}

		public Row row(String name)
		{
			for (Row r : revenueRows)
			{
				if (r.name.equalsIgnoreCase(name))
				{
					return r;
				}
			}
			for (Row r : otherRows)
			{
				if (r.name.equalsIgnoreCase(name))
				{
					return r;
				}
			}
			return null;
		}
	}

	public static final String SHARED_ROW = "Common costs (not spread)";
	public static final String UTILITIES_ROW = "Utilities (not spread)";

	private ActivityMath()
	{
	}

	/** Without keys: pools spread by the fallback only. */
	public static Result compute(LinkedHashMap<String, Double> revenue, Costs costs, Spread spread)
	{
		return compute(revenue, costs, spread, Map.of(), Map.of());
	}

	/**
	 * @param revenue      revenue per revenue activity, every revenue activity present (0 allowed)
	 * @param utilitiesKey percent per activity for the utilities pool (empty = fallback)
	 * @param commonKey    percent per activity for the common pool (empty = fallback)
	 */
	public static Result compute(LinkedHashMap<String, Double> revenue, Costs costs, Spread spread,
								 Map<String, Double> utilitiesKey, Map<String, Double> commonKey)
	{
		double totalRevenue = 0;
		for (double v : revenue.values())
		{
			totalRevenue += v;
		}

		Map<String, Double> utilitiesShare = keyShare(revenue, costs.utilitiesPool, utilitiesKey);
		boolean utilitiesKeyUsed = utilitiesShare != null;
		if (utilitiesShare == null)
		{
			utilitiesShare = spreadShared(revenue, totalRevenue, costs.utilitiesPool, spread);
		}
		Map<String, Double> commonShare = keyShare(revenue, costs.commonPool, commonKey);
		boolean commonKeyUsed = commonShare != null;
		if (commonShare == null)
		{
			commonShare = spreadShared(revenue, totalRevenue, costs.commonPool, spread);
		}

		List<Row> revenueRows = new ArrayList<>();
		for (Map.Entry<String, Double> e : revenue.entrySet())
		{
			Costs.Bucket b = costs.direct.getOrDefault(e.getKey(), new Costs.Bucket());
			EnumMap<Heading, Double> headings = new EnumMap<>(b.headings);
			double util = utilitiesShare.getOrDefault(e.getKey(), 0.0);
			double common = commonShare.getOrDefault(e.getKey(), 0.0);
			headings.merge(Heading.UTILITIES, util, Double::sum);
			headings.merge(Heading.COMMON, common, Double::sum);
			revenueRows.add(new Row(e.getKey(), Kind.REVENUE, e.getValue(), b.total, util + common, b.count, totalRevenue, headings));
		}
		for (Map.Entry<String, Costs.Bucket> e : costs.direct.entrySet())
		{
			if (!revenue.containsKey(e.getKey()))
			{
				revenueRows.add(new Row(e.getKey(), Kind.REVENUE, 0, e.getValue().total, 0, e.getValue().count, totalRevenue,
						new EnumMap<>(e.getValue().headings)));
			}
		}
		revenueRows.sort(Comparator.comparingDouble((Row r) -> r.revenue).reversed()
				.thenComparing(Comparator.comparingDouble((Row r) -> r.directCosts).reversed()));

		List<Row> otherRows = new ArrayList<>();
		double unspreadUtilities = costs.utilitiesPool - utilitiesShare.values().stream().mapToDouble(Double::doubleValue).sum();
		double unspreadCommon = costs.commonPool - commonShare.values().stream().mapToDouble(Double::doubleValue).sum();
		if (Math.abs(unspreadUtilities) >= 0.005)
		{
			EnumMap<Heading, Double> h = new EnumMap<>(Heading.class);
			h.put(Heading.UTILITIES, unspreadUtilities);
			otherRows.add(new Row(UTILITIES_ROW, Kind.SHARED, 0, unspreadUtilities, 0, 0, totalRevenue, h));
		}
		if (Math.abs(unspreadCommon) >= 0.005)
		{
			EnumMap<Heading, Double> h = new EnumMap<>(Heading.class);
			h.put(Heading.COMMON, unspreadCommon);
			otherRows.add(new Row(SHARED_ROW, Kind.SHARED, 0, unspreadCommon, 0, costs.sharedCount, totalRevenue, h));
		}
		for (Map.Entry<String, Costs.Bucket> e : costs.separate.entrySet())
		{
			otherRows.add(new Row(e.getKey(), Kind.SEPARATE, 0, e.getValue().total, 0, e.getValue().count, totalRevenue,
					new EnumMap<>(e.getValue().headings)));
		}
		for (Map.Entry<String, Costs.Bucket> e : costs.unassigned.entrySet())
		{
			otherRows.add(new Row(e.getKey(), Kind.UNASSIGNED, 0, e.getValue().total, 0, e.getValue().count, totalRevenue,
					new EnumMap<>(e.getValue().headings)));
		}
		otherRows.sort(Comparator.comparing((Row r) -> r.kind).thenComparing(Comparator.comparingDouble((Row r) -> r.directCosts).reversed()));

		double totalCosts = costs.shared();
		for (Costs.Bucket b : costs.direct.values())
		{
			totalCosts += b.total;
		}
		for (Costs.Bucket b : costs.separate.values())
		{
			totalCosts += b.total;
		}
		for (Costs.Bucket b : costs.unassigned.values())
		{
			totalCosts += b.total;
		}
		return new Result(revenueRows, otherRows, totalRevenue, totalCosts, costs.shared(),
				costs.unassignedTotal(), costs.unassignedCount(), spread, utilitiesKeyUsed, commonKeyUsed);
	}

	/**
	 * A pool spread by a key: each activity's percent of the pool. Percentages for activities
	 * not in the revenue map are ignored, and the key is scaled to what remains so nothing is
	 * lost. Returns null when there is no usable key.
	 */
	static Map<String, Double> keyShare(LinkedHashMap<String, Double> revenue, double pool, Map<String, Double> key)
	{
		if (key == null || key.isEmpty())
		{
			return null;
		}
		double sum = 0;
		for (Map.Entry<String, Double> e : key.entrySet())
		{
			if (revenue.containsKey(e.getKey()) && e.getValue() > 0)
			{
				sum += e.getValue();
			}
		}
		if (sum <= 0)
		{
			return null;
		}
		Map<String, Double> out = new LinkedHashMap<>();
		if (pool == 0)
		{
			return out;
		}
		for (Map.Entry<String, Double> e : key.entrySet())
		{
			if (revenue.containsKey(e.getKey()) && e.getValue() > 0)
			{
				out.put(e.getKey(), pool * e.getValue() / sum);
			}
		}
		return out;
	}

	/** Shared costs per revenue activity by the fallback; empty when not spread. */
	static Map<String, Double> spreadShared(LinkedHashMap<String, Double> revenue, double totalRevenue, double shared, Spread spread)
	{
		Map<String, Double> out = new LinkedHashMap<>();
		if (spread == Spread.NONE || shared == 0 || revenue.isEmpty())
		{
			return out;
		}
		if (spread == Spread.REVENUE && totalRevenue > 0)
		{
			for (Map.Entry<String, Double> e : revenue.entrySet())
			{
				if (e.getValue() > 0)
				{
					out.put(e.getKey(), shared * e.getValue() / totalRevenue);
				}
			}
			return out;
		}
		List<String> earning = new ArrayList<>();
		for (Map.Entry<String, Double> e : revenue.entrySet())
		{
			if (e.getValue() > 0)
			{
				earning.add(e.getKey());
			}
		}
		if (earning.isEmpty())
		{
			earning.addAll(revenue.keySet());
		}
		for (String name : earning)
		{
			out.put(name, shared / earning.size());
		}
		return out;
	}
}
