package com.cslsm.web.activities;

import com.cslsm.web.activities.ActivityAnalysisRepository.CostLine;
import com.cslsm.web.activities.ActivityAnalysisRepository.ExpenseLine;
import com.cslsm.web.activities.ActivityMath.Result;
import com.cslsm.web.activities.ActivityMath.Row;
import com.cslsm.web.activities.ActivityMath.Spread;
import com.cslsm.web.activities.ActivityRules.Def;
import com.cslsm.web.activities.ActivityRules.Kind;
import com.cslsm.web.activities.ActivityRules.Target;
import com.cslsm.web.expenses.ExpenseOptionRepository;
import com.cslsm.web.finance.FinanceModels.NamedAmount;
import com.cslsm.web.finance.FinanceRepository;
import com.cslsm.web.finance.MonthlyChartSvg;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class ActivityAnalysisService
{
	private static final int EXPENSE_LIST_LIMIT = 300;

	private final ExpenseOptionRepository options;
	private final ActivityAnalysisRepository repo;
	private final CostKeyRepository keys;

	public ActivityAnalysisService(ExpenseOptionRepository options, ActivityAnalysisRepository repo, CostKeyRepository keys)
	{
		this.options = options;
		this.repo = repo;
		this.keys = keys;
	}

	/** Costs of a period collected per target, ready for ActivityMath. */
	private ActivityMath.Costs costsOf(List<CostLine> lines, ActivityRules rules)
	{
		ActivityMath.Costs costs = new ActivityMath.Costs();
		for (CostLine l : lines)
		{
			costs.add(rules.resolve(l.activity()), l.heading(), l.percent(), l.amount(), l.expenses());
		}
		return costs;
	}

	private Result compute(LinkedHashMap<String, Double> revenue, ActivityMath.Costs costs, Spread spread)
	{
		return ActivityMath.compute(revenue, costs, spread, keys.key(CostKeyRepository.UTILITIES), keys.key(CostKeyRepository.COMMON));
	}

	/** Current rules, read fresh so settings changes apply immediately. */
	public ActivityRules rules()
	{
		List<Def> defs = new ArrayList<>();
		options.allActivities().forEach(o -> defs.add(new Def(o.id(), o.name(), rule(o.costRule()),
				o.incomeColumn(), o.partOf(), o.active())));
		return new ActivityRules(defs, FinanceRepository::isIncomeColumn);
	}

	private static ActivityRules.Rule rule(String value)
	{
		try
		{
			return ActivityRules.Rule.valueOf(value);
		}
		catch (RuntimeException e)
		{
			return ActivityRules.Rule.SEPARATE;
		}
	}

	/* ======================= overview ======================= */

	public Result analyse(LocalDate from, LocalDate to, Spread spread)
	{
		return analyse(from, to, spread, rules());
	}

	Result analyse(LocalDate from, LocalDate to, Spread spread, ActivityRules rules)
	{
		return compute(repo.revenue(from, to, rules.revenueActivities()), costsOf(repo.costLines(from, to), rules), spread);
	}

	/* ======================= one activity ======================= */

	public record WeekdayStat(String day, double average, int days)
	{
	}

	public record DayAmount(LocalDate date, double amount)
	{
	}

	public record Detail(Def activity, Kind kind, Period period, Spread spread,
						 Row row, Row previousRow, double ownCosts,
						 String chartSvg,
						 List<NamedAmount> costByCategory,
						 List<ExpenseLine> expenses, boolean expensesTruncated,
						 List<WeekdayStat> weekdays, List<DayAmount> bestDays, List<DayAmount> worstDays,
						 int daysLogged, int daysWithoutRevenue,
						 List<NamedAmount> sharedSpread, List<String> partOfNames)
	{
		public boolean isRevenue()
		{
			return kind == Kind.REVENUE;
		}

		public boolean isShared()
		{
			return kind == Kind.SHARED;
		}

		/** Change of profit (revenue activity) or costs (others) vs the previous period, or null. */
		public Double change()
		{
			if (row == null || previousRow == null)
			{
				return null;
			}
			double now = isRevenue() ? row.getProfit() : row.getCosts();
			double before = isRevenue() ? previousRow.getProfit() : previousRow.getCosts();
			return before == 0 ? null : (now - before) / Math.abs(before);
		}
	}

	public Optional<Detail> detail(String name, Period period, Spread spread)
	{
		ActivityRules rules = rules();
		Def def = rules.find(name);
		if (def == null)
		{
			return Optional.empty();
		}
		Target target = rules.resolve(def.name);
		// "Part of X" activities have no page of their own: their costs are X's.
		if (target.kind == Kind.REVENUE && !target.name.equalsIgnoreCase(def.name))
		{
			def = rules.find(target.name);
		}
		final Def activity = def;
		Kind kind = target.kind;

		List<CostLine> lines = repo.costLines(period.from(), period.to());
		Result result = analyse(period.from(), period.to(), spread, rules);
		Result previous = analyse(period.previous().from(), period.previous().to(), spread, rules);

		Row row = kind == Kind.SHARED ? sharedRow(activity, lines, rules) : orEmpty(result.row(activity.name), activity, kind);
		Row previousRow = kind == Kind.SHARED
				? sharedRow(activity, repo.costLines(period.previous().from(), period.previous().to()), rules)
				: orEmpty(previous.row(activity.name), activity, kind);

		// Costs by category: what lands directly on this activity (or this shared pool line)
		Map<String, Double> byCategory = new LinkedHashMap<>();
		double ownCosts = 0;
		for (CostLine l : lines)
		{
			if (belongs(rules, l.activity(), activity, kind))
			{
				byCategory.merge(l.category() == null ? "(none)" : l.category(), l.amount(), Double::sum);
				ownCosts += l.amount();
			}
		}
		List<NamedAmount> costByCategory = byCategory.entrySet().stream()
				.sorted(Map.Entry.<String, Double>comparingByValue().reversed())
				.map(e -> new NamedAmount(e.getKey(), e.getValue()))
				.toList();

		// A shared activity: how its costs were spread over the revenue activities
		List<NamedAmount> sharedSpread = new ArrayList<>();
		if (kind == Kind.SHARED && result.sharedTotal > 0)
		{
			for (Row r : result.revenueRows)
			{
				if (r.sharedCosts > 0)
				{
					sharedSpread.add(new NamedAmount(r.name, ownCosts * r.sharedCosts / result.sharedTotal));
				}
			}
		}

		List<ExpenseLine> expenses = repo.expenses(period.from(), period.to(), rules.namesCountingAs(activity.name), EXPENSE_LIST_LIMIT + 1);
		boolean truncated = expenses.size() > EXPENSE_LIST_LIMIT;
		if (truncated)
		{
			expenses = expenses.subList(0, EXPENSE_LIST_LIMIT);
		}

		List<WeekdayStat> weekdays = List.of();
		List<DayAmount> best = List.of();
		List<DayAmount> worst = List.of();
		int daysLogged = 0;
		int daysWithout = 0;
		if (kind == Kind.REVENUE)
		{
			Map<LocalDate, Double> daily = repo.dailyRevenue(period.from(), period.to(), activity);
			daysLogged = daily.size();
			daysWithout = (int) daily.values().stream().filter(v -> v == 0).count();
			weekdays = weekdays(daily);
			List<DayAmount> days = daily.entrySet().stream().map(e -> new DayAmount(e.getKey(), e.getValue())).toList();
			best = days.stream().filter(d -> d.amount() > 0).sorted(Comparator.comparingDouble(DayAmount::amount).reversed()).limit(5).toList();
			worst = days.stream().filter(d -> d.amount() > 0).sorted(Comparator.comparingDouble(DayAmount::amount)).limit(5).toList();
		}

		List<String> partOf = rules.namesCountingAs(activity.name).stream()
				.map(rules::find).filter(d -> d != null && !d.name.equalsIgnoreCase(activity.name))
				.map(d -> d.name).sorted().toList();

		return Optional.of(new Detail(activity, kind, period, spread, row, previousRow, ownCosts,
				chart(activity, kind, period, spread, rules), costByCategory, expenses, truncated,
				weekdays, best, worst, daysLogged, daysWithout, sharedSpread, partOf));
	}

	/** A shared activity has no row in the report (it is spread); build one from its own costs. */
	private static Row sharedRow(Def activity, List<CostLine> lines, ActivityRules rules)
	{
		double amount = 0;
		int count = 0;
		for (CostLine l : lines)
		{
			if (belongs(rules, l.activity(), activity, Kind.SHARED))
			{
				amount += l.amount();
				count += l.expenses();
			}
		}
		return new Row(activity.name, Kind.SHARED, 0, amount, 0, count, 0, new java.util.EnumMap<>(ActivityMath.Heading.class));
	}

	/** An activity with nothing in the period still gets a row of zeros. */
	private static Row orEmpty(Row row, Def activity, Kind kind)
	{
		return row != null ? row : new Row(activity.name, kind, 0, 0, 0, 0, 0, new java.util.EnumMap<>(ActivityMath.Heading.class));
	}

	/** Does a cost line written with this name belong to the activity's own costs? */
	private static boolean belongs(ActivityRules rules, String written, Def activity, Kind kind)
	{
		Target t = rules.resolve(written);
		if (kind == Kind.SHARED)
		{
			Def d = rules.find(written);
			return d != null && d.name.equalsIgnoreCase(activity.name);
		}
		return t.kind == kind && t.name.equalsIgnoreCase(activity.name);
	}

	private static List<WeekdayStat> weekdays(Map<LocalDate, Double> daily)
	{
		Map<DayOfWeek, double[]> acc = new EnumMap<>(DayOfWeek.class);
		daily.forEach((date, amount) -> {
			double[] v = acc.computeIfAbsent(date.getDayOfWeek(), k -> new double[2]);
			v[0] += amount;
			v[1]++;
		});
		List<WeekdayStat> out = new ArrayList<>();
		for (DayOfWeek d : DayOfWeek.values())
		{
			double[] v = acc.getOrDefault(d, new double[2]);
			out.add(new WeekdayStat(d.getDisplayName(TextStyle.FULL, Locale.ENGLISH), v[1] > 0 ? v[0] / v[1] : 0, (int) v[1]));
		}
		return out;
	}

	/** Revenue (0 for cost-only activities) and costs per month over the 12 months ending with the period. */
	private String chart(Def activity, Kind kind, Period period, Spread spread, ActivityRules rules)
	{
		YearMonth last = YearMonth.from(period.to());
		YearMonth first = last.minusMonths(11);
		LocalDate from = first.atDay(1);
		LocalDate to = period.to();
		List<CostLine> lines = repo.costLines(from, to);
		Map<YearMonth, LinkedHashMap<String, Double>> revenue = repo.monthlyRevenue(from, to, rules.revenueActivities());

		List<MonthlyChartSvg.Point> points = new ArrayList<>();
		for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1))
		{
			final YearMonth month = m;
			List<CostLine> monthLines = lines.stream().filter(l -> l.month().equals(month)).toList();
			ActivityMath.Costs costs = costsOf(monthLines, rules);
			double own = 0;
			for (CostLine l : monthLines)
			{
				if (belongs(rules, l.activity(), activity, kind))
				{
					own += l.amount();
				}
			}
			LinkedHashMap<String, Double> monthRevenue = new LinkedHashMap<>();
			for (Def d : rules.revenueActivities())
			{
				monthRevenue.put(d.name, revenue.getOrDefault(month, new LinkedHashMap<>()).getOrDefault(d.name, 0.0));
			}
			if (kind == Kind.REVENUE)
			{
				Row r = compute(monthRevenue, costs, spread).row(activity.name);
				points.add(new MonthlyChartSvg.Point(month, r == null ? 0 : r.revenue, r == null ? 0 : r.getCosts()));
			}
			else
			{
				points.add(new MonthlyChartSvg.Point(month, 0, own));
			}
		}
		return MonthlyChartSvg.render(points);
	}
}
