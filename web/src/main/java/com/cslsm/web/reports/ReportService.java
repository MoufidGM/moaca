package com.cslsm.web.reports;

import com.cslsm.web.activities.ActivityAnalysisRepository;
import com.cslsm.web.activities.ActivityAnalysisRepository.CostLine;
import com.cslsm.web.activities.ActivityAnalysisService;
import com.cslsm.web.activities.ActivityMath;
import com.cslsm.web.finance.BarChartSvg;
import com.cslsm.web.finance.FinanceModels.NamedAmount;
import com.cslsm.web.finance.FinanceRepository;
import com.cslsm.web.finance.MonthlyChartSvg;
import com.cslsm.web.reports.ReportTable.Cell;
import com.cslsm.web.reports.ReportTable.Kind;
import com.cslsm.web.reports.ReportTable.Row;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The yearly reports. Revenue here is the center's income (departments + drinks) plus Tiki
 * Taka's sales, so the net profit matches the Activities page. Months that have not started
 * yet are left empty rather than shown as zero.
 */
@Service
public class ReportService
{
	public record Def(String key, String title, String blurb)
	{
	}

	public static final List<Def> DEFS = List.of(
			new Def("pnl", "Profit & loss", "Revenue, expenses and net profit, month by month"),
			new Def("expenses-category", "Expenses by category", "Every category, month by month"),
			new Def("expenses-activity", "Expenses by activity", "What each activity cost, split expenses counted on each part"),
			new Def("payments", "Payment methods", "Cash, card, cheque and drinks, month by month"),
			new Def("yoy", "Year over year", "Each month against the same month of the previous year"),
			new Def("activities", "Profit by activity", "Revenue, costs and profit of every activity over the year"));

	public record Report(Def def, int year, String note, ReportTable table, String chartSvg)
	{
	}

	/** How far back the year selector goes, whatever typos are in the data. */
	private static final int MAX_YEARS_BACK = 10;

	private static final List<String[]> HEADINGS = List.of(
			new String[]{"SALARIES", "Salaries"},
			new String[]{"MAINTENANCE", "Maintenance and services"},
			new String[]{"PURCHASES", "Purchases and equipment"},
			new String[]{"UTILITIES", "Electricity, water, phone, internet"},
			new String[]{"OTHER", "Other"});

	private final FinanceRepository finance;
	private final ActivityAnalysisRepository costs;
	private final ActivityAnalysisService analysis;

	public ReportService(FinanceRepository finance, ActivityAnalysisRepository costs, ActivityAnalysisService analysis)
	{
		this.finance = finance;
		this.costs = costs;
		this.analysis = analysis;
	}

	public static Optional<Def> def(String key)
	{
		return DEFS.stream().filter(d -> d.key().equals(key)).findFirst();
	}

	/** Years with data, oldest first, always including this year. */
	public List<Integer> years(LocalDate today)
	{
		int first = today.getYear();
		for (Optional<LocalDate> d : List.of(finance.firstLogDate(), finance.firstExpenseDate()))
		{
			if (d.isPresent())
			{
				first = Math.min(first, Math.max(d.get().getYear(), today.getYear() - MAX_YEARS_BACK));
			}
		}
		List<Integer> out = new ArrayList<>();
		for (int y = first; y <= today.getYear(); y++)
		{
			out.add(y);
		}
		return out;
	}

	public Report build(Def def, int year, LocalDate today)
	{
		return switch (def.key())
		{
			case "expenses-category" -> expensesBy(def, year, today, l -> l.category() == null || l.category().isBlank() ? "(no category)" : l.category());
			case "expenses-activity" -> expensesBy(def, year, today, l -> l.activity() == null || l.activity().isBlank() ? "(no activity)" : l.activity().trim());
			case "payments" -> payments(def, year, today);
			case "yoy" -> yearOverYear(def, year, today);
			case "activities" -> activities(def, year, today);
			default -> profitAndLoss(def, year, today);
		};
	}

	/* ======================= a year's months ======================= */

	/** The 12 months of a year with the "Total" column; months after today stay empty. */
	private static final class Months
	{
		final int year;
		final LocalDate from;
		final LocalDate to;
		/** 0-based index of the last month that has started; -1 for a future year. */
		final int last;

		Months(int year, LocalDate today)
		{
			this.year = year;
			this.from = LocalDate.of(year, 1, 1);
			LocalDate end = LocalDate.of(year, 12, 31);
			this.to = end.isAfter(today) ? today : end;
			this.last = year < today.getYear() ? 11 : year == today.getYear() ? today.getMonthValue() - 1 : -1;
		}

		List<String> columns()
		{
			List<String> out = new ArrayList<>();
			for (Month m : Month.values())
			{
				out.add(m.getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
			}
			out.add("Total");
			return out;
		}

		/** A row's 12 values from a per-month map, plus the total. */
		List<Double> values(Map<YearMonth, Double> byMonth)
		{
			List<Double> out = new ArrayList<>();
			for (int i = 0; i < 12; i++)
			{
				out.add(i > last ? null : byMonth.getOrDefault(YearMonth.of(year, i + 1), 0.0));
			}
			return withTotal(out);
		}

		static List<Double> withTotal(List<Double> months)
		{
			List<Double> out = new ArrayList<>(months);
			Double total = null;
			for (Double v : months)
			{
				if (v != null)
				{
					total = (total == null ? 0 : total) + v;
				}
			}
			out.add(total);
			return out;
		}

		boolean hasData(List<Double> values)
		{
			Double total = values.get(values.size() - 1);
			return total != null && total != 0;
		}
	}

	/* ======================= profit & loss ======================= */

	private Report profitAndLoss(Def def, int year, LocalDate today)
	{
		Months months = new Months(year, today);
		List<Row> rows = new ArrayList<>();
		List<List<Double>> revenueLines = new ArrayList<>();

		rows.add(Row.section("Revenue"));
		Map<YearMonth, List<NamedAmount>> byActivity = finance.monthlyIncomeByActivity(months.from, months.to);
		for (int i = 0; i < FinanceRepository.ACTIVITIES.size(); i++)
		{
			Map<YearMonth, Double> line = new LinkedHashMap<>();
			for (Map.Entry<YearMonth, List<NamedAmount>> e : byActivity.entrySet())
			{
				line.put(e.getKey(), e.getValue().get(i).amount());
			}
			List<Double> values = months.values(line);
			if (months.hasData(values))
			{
				rows.add(Row.of(FinanceRepository.ACTIVITIES.get(i).name(), values));
				revenueLines.add(values);
			}
		}
		List<Double> restaurant = months.values(finance.monthlyRestaurantSales(months.from, months.to));
		if (months.hasData(restaurant))
		{
			rows.add(Row.of("Tiki Taka (restaurant)", restaurant));
			revenueLines.add(restaurant);
		}
		List<Double> salon = months.values(finance.monthlySalonSales(months.from, months.to));
		if (months.hasData(salon))
		{
			rows.add(Row.of("Salon", salon));
			revenueLines.add(salon);
		}
		List<Double> revenue = ReportTable.sumColumns(revenueLines, 13);
		rows.add(new Row("Total revenue", cells(revenue), Kind.SUBTOTAL, false));

		rows.add(Row.section("Expenses"));
		Map<String, Map<YearMonth, Double>> byHeading = group(costs.costLines(months.from, months.to), CostLine::heading);
		List<List<Double>> expenseLines = new ArrayList<>();
		for (String[] h : HEADINGS)
		{
			List<Double> values = months.values(byHeading.getOrDefault(h[0], Map.of()));
			if (months.hasData(values))
			{
				rows.add(Row.of(h[1], values));
				expenseLines.add(values);
			}
		}
		List<Double> expenses = ReportTable.sumColumns(expenseLines, 13);
		rows.add(new Row("Total expenses", cells(expenses), Kind.SUBTOTAL, false));

		List<Double> net = new ArrayList<>();
		List<Cell> margin = new ArrayList<>();
		for (int c = 0; c < 13; c++)
		{
			Double r = revenue.get(c);
			Double x = expenses.get(c);
			Double n = r == null && x == null ? null : (r == null ? 0 : r) - (x == null ? 0 : x);
			net.add(n);
			margin.add(r == null || r == 0 || n == null ? Cell.EMPTY : Cell.percent(n / r));
		}
		rows.add(new Row("Net profit", cells(net), Kind.TOTAL, true));
		rows.add(new Row("Margin", margin, Kind.NORMAL, false));

		List<MonthlyChartSvg.Point> points = new ArrayList<>();
		for (int i = 0; i <= months.last && i < 12; i++)
		{
			points.add(new MonthlyChartSvg.Point(YearMonth.of(year, i + 1), orZero(revenue.get(i)), orZero(expenses.get(i))));
		}
		return new Report(def, year, "Revenue is the center's income: the departments and drinks of the daily log, plus Tiki Taka's and the Salon's sales. "
				+ "Expenses include pending ones until an admin rejects them; bank deposits recorded as expenses are left out once fixed.",
				new ReportTable(months.columns(), rows), MonthlyChartSvg.render(points));
	}

	/* ======================= expenses by category / activity ======================= */

	private Report expensesBy(Def def, int year, LocalDate today, Function<CostLine, String> key)
	{
		Months months = new Months(year, today);
		Map<String, Map<YearMonth, Double>> grouped = group(costs.costLines(months.from, months.to), key);
		List<Row> rows = new ArrayList<>();
		List<List<Double>> lines = new ArrayList<>();
		grouped.entrySet().stream()
				.map(e -> Map.entry(e.getKey(), months.values(e.getValue())))
				.sorted((a, b) -> Double.compare(orZero(b.getValue().get(12)), orZero(a.getValue().get(12))))
				.forEach(e -> {
					rows.add(Row.of(e.getKey(), e.getValue()));
					lines.add(e.getValue());
				});
		rows.add(new Row("Total", cells(ReportTable.sumColumns(lines, 13)), Kind.TOTAL, false));
		String note = "expenses-activity".equals(def.key())
				? "An expense split across activities counts on each of them for its share. Names are shown as written on the expenses; "
				+ "old spellings can be merged on Activity settings."
				: "Pending expenses count until an admin rejects them; rejected ones never count.";
		return new Report(def, year, note, new ReportTable(months.columns(), rows), null);
	}

	/** Amounts per key and month, keys in first-seen order (case-insensitive). */
	private static Map<String, Map<YearMonth, Double>> group(List<CostLine> lines, Function<CostLine, String> key)
	{
		Map<String, String> spelling = new LinkedHashMap<>();
		Map<String, Map<YearMonth, Double>> out = new LinkedHashMap<>();
		for (CostLine l : lines)
		{
			String k = key.apply(l);
			String canonical = spelling.computeIfAbsent(k.toLowerCase(Locale.ROOT), x -> k);
			out.computeIfAbsent(canonical, x -> new LinkedHashMap<>()).merge(l.month(), l.amount(), Double::sum);
		}
		return out;
	}

	/* ======================= payment methods ======================= */

	private Report payments(Def def, int year, LocalDate today)
	{
		Months months = new Months(year, today);
		Map<YearMonth, List<NamedAmount>> mix = finance.monthlyPaymentMix(months.from, months.to);
		List<String> names = FinanceRepository.PAYMENT_LINES;
		List<Row> rows = new ArrayList<>();
		List<List<Double>> lines = new ArrayList<>();
		for (int i = 0; i < names.size(); i++)
		{
			Map<YearMonth, Double> line = new LinkedHashMap<>();
			for (Map.Entry<YearMonth, List<NamedAmount>> e : mix.entrySet())
			{
				line.put(e.getKey(), e.getValue().get(i).amount());
			}
			List<Double> values = months.values(line);
			rows.add(Row.of(names.get(i), values));
			lines.add(values);
		}
		List<Double> total = ReportTable.sumColumns(lines, 13);
		rows.add(new Row("Total income", cells(total), Kind.SUBTOTAL, false));
		rows.add(new Row("Cash share (all tills)", shares(ReportTable.sumColumns(List.of(lines.get(0), lines.get(3), lines.get(4), lines.get(6)), 13), total), Kind.NORMAL, false));
		rows.add(new Row("Card share (all tills)", shares(ReportTable.sumColumns(List.of(lines.get(1), lines.get(5), lines.get(7)), 13), total), Kind.NORMAL, false));
		return new Report(def, year, "Cash, card and cheque are the daily log's payment lines for the departments; drinks are paid "
				+ "in cash at the bar. Tiki Taka's and the Salon's sales are entered on their own pages, cash and card apart.", new ReportTable(months.columns(), rows), null);
	}

	private static List<Cell> shares(List<Double> part, List<Double> total)
	{
		List<Cell> out = new ArrayList<>();
		for (int c = 0; c < part.size(); c++)
		{
			Double t = total.get(c);
			out.add(t == null || t == 0 || part.get(c) == null ? Cell.EMPTY : Cell.percent(part.get(c) / t));
		}
		return out;
	}

	/* ======================= year over year ======================= */

	private Report yearOverYear(Def def, int year, LocalDate today)
	{
		int previous = year - 1;
		List<String> columns = List.of("Revenue " + previous, "Revenue " + year, "Change",
				"Expenses " + previous, "Expenses " + year, "Change", "Net " + previous, "Net " + year);
		List<Row> rows = new ArrayList<>();
		double[] totals = new double[6];
		boolean any = false;
		List<Double> chartPrev = new ArrayList<>();
		List<Double> chartNow = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		List<String> titles = new ArrayList<>();
		String partial = null;

		for (Month m : Month.values())
		{
			YearMonth ym = YearMonth.of(year, m);
			if (ym.atDay(1).isAfter(today))
			{
				rows.add(new Row(m.getDisplayName(TextStyle.FULL, Locale.ENGLISH), List.of(Cell.EMPTY, Cell.EMPTY, Cell.EMPTY,
						Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY), Kind.NORMAL, false));
				continue;
			}
			LocalDate from = ym.atDay(1);
			LocalDate to = ym.atEndOfMonth().isAfter(today) ? today : ym.atEndOfMonth();
			// A month still running is compared with the same days of last year
			YearMonth pym = YearMonth.of(previous, m);
			LocalDate pfrom = pym.atDay(1);
			LocalDate pto = to.equals(ym.atEndOfMonth()) ? pym.atEndOfMonth() : pym.atDay(Math.min(to.getDayOfMonth(), pym.lengthOfMonth()));
			if (!to.equals(ym.atEndOfMonth()))
			{
				partial = m.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " is compared up to the " + to.getDayOfMonth() + " of both years.";
			}
			double revPrev = revenue(pfrom, pto);
			double revNow = revenue(from, to);
			double expPrev = finance.expenses(pfrom, pto);
			double expNow = finance.expenses(from, to);
			totals[0] += revPrev;
			totals[1] += revNow;
			totals[2] += expPrev;
			totals[3] += expNow;
			totals[4] += revPrev - expPrev;
			totals[5] += revNow - expNow;
			any = true;
			rows.add(new Row(m.getDisplayName(TextStyle.FULL, Locale.ENGLISH), yoyCells(revPrev, revNow, expPrev, expNow), Kind.NORMAL, true));
			labels.add(m.getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
			titles.add(m.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " · revenue");
			chartPrev.add(revPrev);
			chartNow.add(revNow);
		}
		if (any)
		{
			rows.add(new Row(year == today.getYear() ? "Total so far" : "Total",
					yoyCells(totals[0], totals[1], totals[2], totals[3]), Kind.TOTAL, true));
		}
		String chart = any ? BarChartSvg.render("Revenue per month, " + previous + " and " + year, labels, titles,
				new BarChartSvg.Series(String.valueOf(year), "bar-income", chartNow),
				new BarChartSvg.Series(String.valueOf(previous), "bar-prev", chartPrev)) : "";
		return new Report(def, year, "Revenue is the center's income, Tiki Taka and the Salon included, as in the profit & loss."
				+ (partial == null ? "" : " " + partial), new ReportTable(columns, rows), chart);
	}

	private double revenue(LocalDate from, LocalDate to)
	{
		return finance.income(from, to);
	}

	private static List<Cell> yoyCells(double revPrev, double revNow, double expPrev, double expNow)
	{
		return List.of(Cell.money(revPrev), Cell.money(revNow), change(revPrev, revNow),
				Cell.money(expPrev), Cell.money(expNow), change(expPrev, expNow),
				Cell.money(revPrev - expPrev), Cell.money(revNow - expNow));
	}

	private static Cell change(double before, double now)
	{
		return before == 0 ? Cell.EMPTY : Cell.percent((now - before) / Math.abs(before));
	}

	/* ======================= profit by activity ======================= */

	private Report activities(Def def, int year, LocalDate today)
	{
		Months months = new Months(year, today);
		ActivityMath.Result result = analysis.analyse(months.from, months.to, ActivityMath.Spread.REVENUE);
		List<String> columns = List.of("Revenue", "Direct costs", "Share of common costs", "Total costs", "Profit", "Margin");
		List<Row> rows = new ArrayList<>();
		double direct = 0;
		double shared = 0;
		for (ActivityMath.Row r : result.revenueRows)
		{
			if (r.revenue == 0 && r.getCosts() == 0)
			{
				continue;
			}
			direct += r.directCosts;
			shared += r.sharedCosts;
			rows.add(new Row(r.name, List.of(Cell.money(r.revenue), Cell.money(r.directCosts), Cell.money(r.sharedCosts),
					Cell.money(r.getCosts()), Cell.money(r.getProfit()), r.getMargin() == null ? Cell.EMPTY : Cell.percent(r.getMargin())),
					Kind.NORMAL, true));
		}
		if (!result.otherRows.isEmpty())
		{
			rows.add(Row.section("Costs with no revenue of their own"));
			for (ActivityMath.Row r : result.otherRows)
			{
				direct += r.getCosts();
				rows.add(new Row(r.name, List.of(Cell.EMPTY, Cell.money(r.getCosts()), Cell.EMPTY, Cell.money(r.getCosts()),
						Cell.money(-r.getCosts()), Cell.EMPTY), Kind.NORMAL, true));
			}
		}
		rows.add(new Row("Center", List.of(Cell.money(result.totalRevenue), Cell.money(direct), Cell.money(shared),
				Cell.money(result.totalCosts), Cell.money(result.getProfit()),
				result.totalRevenue == 0 ? Cell.EMPTY : Cell.percent(result.getProfit() / result.totalRevenue)), Kind.TOTAL, true));
		return new Report(def, year, "Common costs are shared in proportion to revenue, as on the Activities page by default. "
				+ "Open Activities for other ways of sharing them, and each activity's own page.", new ReportTable(columns, rows), null);
	}

	/* ======================= helpers ======================= */

	private static List<Cell> cells(List<Double> values)
	{
		return values.stream().map(Cell::money).toList();
	}

	private static double orZero(Double v)
	{
		return v == null ? 0 : v;
	}
}
