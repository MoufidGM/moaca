package com.cslsm.web.activities;

import com.cslsm.web.finance.ExpenseSql;
import com.cslsm.web.finance.FinanceRepository;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Queries behind the per-activity analysis. Split expenses are expanded through
 * expense_allocation: an expense without a split counts 100% for its own activity.
 */
@Repository
@DependsOn("flyway")
public class ActivityAnalysisRepository
{
	/** Activity written on the expense or on its split line. */
	private static final String ACTIVITY = "COALESCE(a.activity, e.activity)";
	/** The part of the expense that falls on that activity. */
	private static final String PORTION = "e.amount * COALESCE(a.percent, 100) / 100.0";
	private static final String FROM_EXPENSES = " FROM expense e LEFT JOIN expense_allocation a ON a.expense_id = e.id"
			+ " WHERE e." + ExpenseSql.COUNTS + " AND e.expense_date BETWEEN ? AND ?";

	/** heading: the category's heading (SALARIES…); percent: 100, or the split share. */
	public record CostLine(String activity, String category, String heading, double percent, YearMonth month,
						   double amount, int expenses)
	{
	}

	/** Tiki Taka's income comes from restaurant_sales, not the daily log. */
	public static final String RESTAURANT_COLUMN = "restaurant";
	/** The Salon's income comes from salon_sales, entered by the reception. */
	public static final String SALON_COLUMN = "salon";

	/** Income columns that are a sales table rather than a daily_summary column, and that table. */
	private static final Map<String, String> SALES_TABLES = Map.of(RESTAURANT_COLUMN, "restaurant_sales", SALON_COLUMN, "salon_sales");

	public static boolean isSalesTable(String incomeColumn)
	{
		return incomeColumn != null && SALES_TABLES.containsKey(incomeColumn);
	}

	public record ExpenseLine(long id, LocalDate date, String category, String description, String activity,
							  double fullAmount, double percent, String status)
	{
		public double portion()
		{
			return fullAmount * percent / 100.0;
		}

		public boolean isSplit()
		{
			return percent < 100;
		}

		public boolean isPending()
		{
			return "PENDING".equals(status);
		}
	}

	/** An activity name found on expenses that is not in the activity list. */
	public record UnknownName(String name, int expenses, double amount)
	{
		public boolean isBlank()
		{
			return name == null || name.isBlank();
		}
	}

	private final JdbcTemplate jdbc;

	public ActivityAnalysisRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	/** Costs by activity, category, split share and month (pending included, rejected excluded). */
	public List<CostLine> costLines(LocalDate from, LocalDate to)
	{
		return jdbc.query("SELECT " + ACTIVITY + " AS act, e.category AS category,"
						+ " COALESCE((SELECT o.heading FROM expense_option o WHERE o.kind = 'CATEGORY' AND o.name = e.category), 'OTHER') AS heading,"
						+ " CASE WHEN COALESCE(a.percent, 100) < 100 THEN 50 ELSE 100 END AS pct,"
						+ " substr(e.expense_date, 1, 7) AS ym, SUM(" + PORTION + ") AS amount, COUNT(DISTINCT e.id) AS n"
						+ FROM_EXPENSES + " GROUP BY act, category, heading, pct, ym",
				(rs, i) -> new CostLine(rs.getString("act"), rs.getString("category"), rs.getString("heading"),
						rs.getDouble("pct"), YearMonth.parse(rs.getString("ym")), rs.getDouble("amount"), rs.getInt("n")),
				from.toString(), to.toString());
	}

	/** Revenue per revenue activity, in the order given; every activity present (0 when none). */
	public LinkedHashMap<String, Double> revenue(LocalDate from, LocalDate to, List<ActivityRules.Def> activities)
	{
		LinkedHashMap<String, Double> out = new LinkedHashMap<>();
		List<ActivityRules.Def> dailyLog = activities.stream().filter(d -> !isSalesTable(d.incomeColumn)).toList();
		if (!dailyLog.isEmpty())
		{
			String columns = columns(dailyLog);
			jdbc.query("SELECT " + columns + " FROM daily_summary WHERE log_date BETWEEN ? AND ?", rs -> {
				for (int i = 0; i < dailyLog.size(); i++)
				{
					out.put(dailyLog.get(i).name, rs.getDouble(i + 1));
				}
			}, from.toString(), to.toString());
		}
		for (ActivityRules.Def d : activities)
		{
			if (isSalesTable(d.incomeColumn))
			{
				out.put(d.name, salesRevenue(d.incomeColumn, from, to));
			}
		}
		// Keep the given order, every activity present
		LinkedHashMap<String, Double> ordered = new LinkedHashMap<>();
		for (ActivityRules.Def d : activities)
		{
			ordered.put(d.name, out.getOrDefault(d.name, 0.0));
		}
		return ordered;
	}

	public double restaurantRevenue(LocalDate from, LocalDate to)
	{
		return salesRevenue(RESTAURANT_COLUMN, from, to);
	}

	/** Cash + card of a sales table (restaurant_sales, salon_sales) over a period. */
	public double salesRevenue(String incomeColumn, LocalDate from, LocalDate to)
	{
		Double v = jdbc.queryForObject("SELECT COALESCE(SUM(cash + card), 0) FROM " + salesTable(incomeColumn) + " WHERE sale_date BETWEEN ? AND ?",
				Double.class, from.toString(), to.toString());
		return v == null ? 0 : v;
	}

	/** The table behind a sales income column — a constant from SALES_TABLES, never user input. */
	private static String salesTable(String incomeColumn)
	{
		String table = SALES_TABLES.get(incomeColumn);
		if (table == null)
		{
			throw new IllegalStateException("Not a sales income column: " + incomeColumn);
		}
		return table;
	}

	/** Revenue per month and activity. */
	public Map<YearMonth, LinkedHashMap<String, Double>> monthlyRevenue(LocalDate from, LocalDate to, List<ActivityRules.Def> activities)
	{
		Map<YearMonth, LinkedHashMap<String, Double>> out = new LinkedHashMap<>();
		List<ActivityRules.Def> dailyLog = activities.stream().filter(d -> !isSalesTable(d.incomeColumn)).toList();
		if (!dailyLog.isEmpty())
		{
			jdbc.query("SELECT substr(log_date, 1, 7) AS ym, " + columns(dailyLog)
					+ " FROM daily_summary WHERE log_date BETWEEN ? AND ? GROUP BY ym", rs -> {
				LinkedHashMap<String, Double> month = out.computeIfAbsent(YearMonth.parse(rs.getString("ym")), k -> new LinkedHashMap<>());
				for (int i = 0; i < dailyLog.size(); i++)
				{
					month.put(dailyLog.get(i).name, rs.getDouble(i + 2));
				}
			}, from.toString(), to.toString());
		}
		for (ActivityRules.Def d : activities)
		{
			if (isSalesTable(d.incomeColumn))
			{
				jdbc.query("SELECT substr(sale_date, 1, 7) AS ym, COALESCE(SUM(cash + card), 0) FROM " + salesTable(d.incomeColumn)
						+ " WHERE sale_date BETWEEN ? AND ? GROUP BY ym", rs -> {
					out.computeIfAbsent(YearMonth.parse(rs.getString(1)), k -> new LinkedHashMap<>()).put(d.name, rs.getDouble(2));
				}, from.toString(), to.toString());
			}
		}
		return out;
	}

	/** One activity's revenue per day, for days with an imported log file (or a sales entry). */
	public Map<LocalDate, Double> dailyRevenue(LocalDate from, LocalDate to, ActivityRules.Def activity)
	{
		Map<LocalDate, Double> out = new LinkedHashMap<>();
		if (isSalesTable(activity.incomeColumn))
		{
			jdbc.query("SELECT sale_date, cash + card FROM " + salesTable(activity.incomeColumn) + " WHERE sale_date BETWEEN ? AND ? ORDER BY sale_date", rs -> {
				out.put(LocalDate.parse(rs.getString(1)), rs.getDouble(2));
			}, from.toString(), to.toString());
			return out;
		}
		if (!FinanceRepository.isIncomeColumn(activity.incomeColumn))
		{
			return out;
		}
		jdbc.query("SELECT log_date, COALESCE(" + activity.incomeColumn + ", 0) FROM daily_summary"
				+ " WHERE log_date BETWEEN ? AND ? ORDER BY log_date", rs -> {
			out.put(LocalDate.parse(rs.getString(1)), rs.getDouble(2));
		}, from.toString(), to.toString());
		return out;
	}

	/** Expenses (or their split part) whose activity is one of the given names (lower case). */
	public List<ExpenseLine> expenses(LocalDate from, LocalDate to, Set<String> lowerNames, int limit)
	{
		if (lowerNames.isEmpty())
		{
			return List.of();
		}
		String placeholders = lowerNames.stream().map(n -> "?").collect(Collectors.joining(", "));
		List<Object> args = new ArrayList<>();
		args.add(from.toString());
		args.add(to.toString());
		args.addAll(lowerNames);
		args.add(limit);
		return jdbc.query("SELECT e.id, e.expense_date, e.category, e.description, " + ACTIVITY + " AS act, e.amount,"
						+ " COALESCE(a.percent, 100) AS pct, e.status"
						+ FROM_EXPENSES + " AND lower(trim(" + ACTIVITY + ")) IN (" + placeholders + ")"
						+ " ORDER BY e.expense_date DESC, e.id DESC LIMIT ?",
				(rs, i) -> new ExpenseLine(rs.getLong("id"), LocalDate.parse(rs.getString("expense_date")),
						rs.getString("category"), rs.getString("description"), rs.getString("act"),
						rs.getDouble("amount"), rs.getDouble("pct"), rs.getString("status")),
				args.toArray());
	}

	/** Every activity name written on expenses, with how often and how much (rejected excluded). */
	public List<UnknownName> activityNamesInUse()
	{
		return jdbc.query("""
						SELECT CASE WHEN activity IS NULL OR trim(activity) = '' THEN NULL ELSE activity END AS act,
						       COUNT(*) AS n, COALESCE(SUM(amount), 0) AS total
						FROM expense WHERE status <> 'REJECTED'
						GROUP BY act ORDER BY total DESC
						""",
				(rs, i) -> new UnknownName(rs.getString("act"), rs.getInt("n"), rs.getDouble("total")));
	}

	/**
	 * Renames an activity on every expense and split line that uses exactly that spelling
	 * (null = expenses with no activity). Returns the number of expenses changed.
	 */
	public int renameActivity(String from, String to)
	{
		if (from == null)
		{
			return jdbc.update("UPDATE expense SET activity = ? WHERE activity IS NULL OR trim(activity) = ''", to);
		}
		int n = jdbc.update("UPDATE expense SET activity = ? WHERE activity = ?", to, from);
		jdbc.update("UPDATE OR IGNORE expense_allocation SET activity = ? WHERE activity = ?", to, from);
		jdbc.update("UPDATE OR IGNORE employee_split SET activity = ? WHERE activity = ?", to, from);
		jdbc.update("UPDATE OR IGNORE cost_key SET activity = ? WHERE activity = ?", to, from);
		return n;
	}

	/** SUM(...) for each activity's daily_summary column — only columns FinanceRepository knows. */
	private static String columns(List<ActivityRules.Def> activities)
	{
		return activities.stream()
				.map(d -> {
					if (!FinanceRepository.isIncomeColumn(d.incomeColumn))
					{
						throw new IllegalStateException("Unknown income column for " + d.name);
					}
					return "COALESCE(SUM(" + d.incomeColumn + "), 0)";
				})
				.collect(Collectors.joining(", "));
	}
}
