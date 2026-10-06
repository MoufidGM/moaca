package com.cslsm.web.income;

import com.cslsm.web.finance.BarChartSvg;
import com.cslsm.web.finance.DashboardService.Delta;
import com.cslsm.web.finance.FinanceModels.NamedAmount;
import com.cslsm.web.finance.FinanceRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Income page: the center's income (departments + drinks + Tiki Taka + Salon) for a period, by
 * activity and by payment method, day by day or month by month, optionally beside the
 * previous period. A day counts as logged when its daily log file was imported; the
 * restaurant's sales are added to that day.
 */
@Service
public class IncomeService
{
	private static final DateTimeFormatter DAY_TITLE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
	private static final DateTimeFormatter MONTH_TITLE = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
	private static final int MAX_MISSING_LISTED = 31;

	private final FinanceRepository finance;

	public IncomeService(FinanceRepository finance)
	{
		this.finance = finance;
	}

	public record ActivityRow(String name, double amount, double share, Double previous, Delta delta)
	{
	}

	public record PaymentRow(String name, double amount, double share, Double previous)
	{
	}

	/** The activities of one unit (sports center, Tiki Taka, Salon) with the unit's subtotal. */
	public record UnitGroup(String name, List<ActivityRow> rows, double amount, double share, double previous, Delta delta)
	{
	}

	/** One day or one month; amount is null for a day with no log file (or a month without any). */
	public record Bucket(String label, String title, LocalDate date, Double amount, Double previous, boolean missing)
	{
	}

	public record IncomeView(IncomePeriod period, boolean compare,
							 double total, double previousTotal, Delta delta,
							 int daysLogged, List<LocalDate> missingDays, int missingCount, double dailyAverage, Bucket bestDay,
							 List<ActivityRow> activities, List<UnitGroup> units, List<PaymentRow> payments,
							 String chartSvg, boolean monthly, List<Bucket> buckets)
	{
	}

	public IncomeView build(IncomePeriod p, boolean compare, LocalDate today)
	{
		Map<LocalDate, Double> totals = withSales(finance.dailyTotals(p.from(), p.to()),
				finance.dailyRestaurantSales(p.from(), p.to()), finance.dailySalonSales(p.from(), p.to()));
		Map<LocalDate, Double> previousTotals = withSales(finance.dailyTotals(p.compareFrom(), p.compareTo()),
				finance.dailyRestaurantSales(p.compareFrom(), p.compareTo()), finance.dailySalonSales(p.compareFrom(), p.compareTo()));
		double total = totals.values().stream().mapToDouble(Double::doubleValue).sum();
		double previousTotal = previousTotals.values().stream().mapToDouble(Double::doubleValue).sum();

		// Days that should have a log file: before today, and since the first file ever imported
		LocalDate firstLog = finance.firstLogDate().orElse(null);
		List<LocalDate> missing = new ArrayList<>();
		if (firstLog != null)
		{
			for (LocalDate d = p.from(); !d.isAfter(p.to()); d = d.plusDays(1))
			{
				if (d.isBefore(today) && !d.isBefore(firstLog) && !totals.containsKey(d))
				{
					missing.add(d);
				}
			}
		}
		Bucket best = null;
		for (Map.Entry<LocalDate, Double> e : totals.entrySet())
		{
			if (best == null || e.getValue() > best.amount())
			{
				best = new Bucket(DAY_TITLE.format(e.getKey()), DAY_TITLE.format(e.getKey()), e.getKey(), e.getValue(), null, false);
			}
		}

		List<Bucket> buckets = p.monthly() ? monthlyBuckets(p) : dailyBuckets(p, totals, previousTotals, missing);
		String chart = chart(p, compare, buckets);

		return new IncomeView(p, compare, total, previousTotal, Delta.of(total, previousTotal, true),
				totals.size(), missing.size() > MAX_MISSING_LISTED ? List.of() : missing, missing.size(),
				totals.isEmpty() ? 0 : total / totals.size(), best,
				activities(p, total, previousTotal), units(activities(p, total, previousTotal), total, previousTotal), payments(p, total),
				chart, p.monthly(), buckets);
	}

	/** Restaurant and Salon sales join the day's log total; a day with sales but no log file still counts as logged. */
	@SafeVarargs
	private static Map<LocalDate, Double> withSales(Map<LocalDate, Double> log, Map<LocalDate, Double>... sales)
	{
		Map<LocalDate, Double> out = new java.util.TreeMap<>(log);
		for (Map<LocalDate, Double> table : sales)
		{
			table.forEach((d, v) -> out.merge(d, v, Double::sum));
		}
		return out;
	}

	private List<Bucket> dailyBuckets(IncomePeriod p, Map<LocalDate, Double> totals, Map<LocalDate, Double> previousTotals,
									  List<LocalDate> missing)
	{
		List<Bucket> out = new ArrayList<>();
		LocalDate prev = p.compareFrom();
		for (LocalDate d = p.from(); !d.isAfter(p.to()); d = d.plusDays(1), prev = prev.plusDays(1))
		{
			String label = p.unit() == IncomePeriod.Unit.WEEK || p.unit() == IncomePeriod.Unit.DAY
					? d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getDayOfMonth()
					: String.valueOf(d.getDayOfMonth());
			out.add(new Bucket(label, DAY_TITLE.format(d), d, totals.get(d), previousTotals.get(prev), missing.contains(d)));
		}
		return out;
	}

	private List<Bucket> monthlyBuckets(IncomePeriod p)
	{
		Map<YearMonth, Double> months = finance.monthlyIncome(p.from(), p.to());
		Map<YearMonth, Double> previous = finance.monthlyIncome(p.compareFrom(), p.compareTo());
		List<Bucket> out = new ArrayList<>();
		YearMonth prev = YearMonth.from(p.compareFrom());
		for (YearMonth m = YearMonth.from(p.from()); !m.isAfter(YearMonth.from(p.to())); m = m.plusMonths(1), prev = prev.plusMonths(1))
		{
			out.add(new Bucket(m.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH), MONTH_TITLE.format(m), m.atDay(1),
					months.get(m), previous.get(prev), false));
		}
		return out;
	}

	private String chart(IncomePeriod p, boolean compare, List<Bucket> buckets)
	{
		List<String> labels = buckets.stream().map(Bucket::label).toList();
		List<String> titles = buckets.stream().map(Bucket::title).toList();
		BarChartSvg.Series main = new BarChartSvg.Series(p.label(), "bar-income", buckets.stream().map(Bucket::amount).toList());
		BarChartSvg.Series previous = compare
				? new BarChartSvg.Series(p.compareLabel().replaceFirst("^vs ", ""), "bar-prev", buckets.stream().map(Bucket::previous).toList())
				: null;
		return BarChartSvg.render("Income per " + (p.monthly() ? "month" : "day"), labels, titles, main, previous);
	}

	private List<ActivityRow> activities(IncomePeriod p, double total, double previousTotal)
	{
		List<NamedAmount> now = finance.incomeByActivity(p.from(), p.to());
		List<NamedAmount> before = finance.incomeByActivity(p.compareFrom(), p.compareTo());
		List<ActivityRow> rows = new ArrayList<>();
		for (int i = 0; i < now.size(); i++)
		{
			double amount = now.get(i).amount();
			double previous = before.get(i).amount();
			if (amount == 0 && previous == 0)
			{
				continue;
			}
			rows.add(new ActivityRow(now.get(i).name(), amount, total == 0 ? 0 : amount / total, previous, Delta.of(amount, previous, true)));
		}
		rows.sort((a, b) -> Double.compare(b.amount(), a.amount()));
		return rows;
	}

	/** Rows grouped by unit; the center's departments first. Units with nothing in either period are left out. */
	private static List<UnitGroup> units(List<ActivityRow> rows, double total, double previousTotal)
	{
		List<UnitGroup> out = new ArrayList<>();
		for (String unit : List.of("Sports center", FinanceRepository.RESTAURANT, FinanceRepository.SALON))
		{
			List<ActivityRow> mine = rows.stream().filter(r -> unit.equals(unitOf(r.name()))).toList();
			if (mine.isEmpty())
			{
				continue;
			}
			double amount = mine.stream().mapToDouble(ActivityRow::amount).sum();
			double previous = mine.stream().mapToDouble(r -> r.previous() == null ? 0 : r.previous()).sum();
			out.add(new UnitGroup(unit, mine, amount, total == 0 ? 0 : amount / total, previous, Delta.of(amount, previous, true)));
		}
		return out;
	}

	private static String unitOf(String activity)
	{
		return FinanceRepository.RESTAURANT.equals(activity) || FinanceRepository.SALON.equals(activity) ? activity : "Sports center";
	}

	private List<PaymentRow> payments(IncomePeriod p, double total)
	{
		List<NamedAmount> now = finance.paymentMix(p.from(), p.to());
		List<NamedAmount> before = finance.paymentMix(p.compareFrom(), p.compareTo());
		List<PaymentRow> rows = new ArrayList<>();
		for (int i = 0; i < now.size(); i++)
		{
			rows.add(new PaymentRow(now.get(i).name(), now.get(i).amount(), total == 0 ? 0 : now.get(i).amount() / total, before.get(i).amount()));
		}
		return rows;
	}
}
