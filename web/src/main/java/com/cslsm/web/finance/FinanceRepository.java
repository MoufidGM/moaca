package com.cslsm.web.finance;

import com.cslsm.web.finance.FinanceModels.Activity;
import com.cslsm.web.finance.FinanceModels.BankAccount;
import com.cslsm.web.finance.FinanceModels.BankBalance;
import com.cslsm.web.finance.FinanceModels.DaySummary;
import com.cslsm.web.finance.FinanceModels.FutureExpense;
import com.cslsm.web.finance.FinanceModels.Movement;
import com.cslsm.web.finance.FinanceModels.NamedAmount;
import com.cslsm.web.finance.FinanceModels.ReceptionBalance;
import com.cslsm.web.finance.FinanceModels.RestaurantBalance;
import com.cslsm.web.finance.FinanceModels.SafeBalance;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only finance queries over the tables the desktop app already maintains.
 * Dates are stored as ISO text (yyyy-MM-dd), so they are passed as strings.
 *
 * Expense rules (see ExpenseSql): rejected expenses never count; "paid from" falls back to
 * the desktop app's paid_from_storage flag.
 */
@Repository
@DependsOn("flyway")
public class FinanceRepository
{
	/**
	 * Income departments of the daily log in display order, drinks included. Column names are
	 * constants, never user input — ActivityRules checks configured columns against this list.
	 * Tiki Taka (the restaurant) is the one more source of income, from restaurant_sales.
	 */
	public static final List<Activity> ACTIVITIES = List.of(
			new Activity("Location de terrains", "total_terrain"),
			new Activity("Padel", "total_padel"),
			new Activity("Gym", "total_gym"),
			new Activity("Park", "total_park"),
			new Activity("Mini Golf", "total_mini_golf"),
			new Activity("Ping Pong", "total_ping_pong"),
			new Activity("Académie", "total_academy_foot"),
			new Activity("Arts Martiaux", "total_taekwondo"),
			new Activity("Box", "total_box"),
			new Activity("Danse", "total_dance"),
			new Activity("Shoes", "total_shoes"),
			new Activity("Drinks", "drinks_amount_total"));

	/** The restaurant's line in incomeByActivity; its revenue comes from restaurant_sales. */
	public static final String RESTAURANT = "Tiki Taka";
	/** The Salon's line; its revenue comes from salon_sales, entered by the reception. */
	public static final String SALON = "Salon";

	public static boolean isIncomeColumn(String column)
	{
		return column != null && ACTIVITIES.stream().anyMatch(a -> a.column().equals(column));
	}

	private static final String ALL_TIME = "0000-01-01";

	/** Safe movement types that add to the safe. IN also leaves the reception; ADJUST_IN does not. */
	private static final String SAFE_IN_TYPES = "('IN', 'ADJUST_IN')";

	private final JdbcTemplate jdbc;

	public FinanceRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	/* ======================= income & expenses ======================= */

	/** Center income: departments + drinks (the daily log) + Tiki Taka's and the Salon's sales. */
	public double income(LocalDate from, LocalDate to)
	{
		return dailyLogIncome(from, to) + restaurantSales(from, to) + salonSales(from, to);
	}

	/** The daily log's part of the income: departments + drinks. What the reception sees. */
	public double dailyLogIncome(LocalDate from, LocalDate to)
	{
		return sum("SELECT COALESCE(SUM(" + IncomeSql.DAY_INCOME + "), 0) FROM daily_summary WHERE log_date BETWEEN ? AND ?", from, to);
	}

	/** Approved and pending expenses (pending count until an admin rejects them). */
	public double expenses(LocalDate from, LocalDate to)
	{
		return sum("SELECT COALESCE(SUM(amount), 0) FROM expense WHERE " + ExpenseSql.COUNTS
				+ " AND expense_date BETWEEN ? AND ?", from, to);
	}

	/** Center income per month, Tiki Taka and the Salon included. */
	public Map<YearMonth, Double> monthlyIncome(LocalDate from, LocalDate to)
	{
		Map<YearMonth, Double> out = monthly("SELECT substr(log_date, 1, 7) AS ym, COALESCE(SUM(" + IncomeSql.DAY_INCOME + "), 0) AS total "
				+ "FROM daily_summary WHERE log_date BETWEEN ? AND ? GROUP BY ym", from, to);
		monthlyRestaurantSales(from, to).forEach((m, v) -> out.merge(m, v, Double::sum));
		monthlySalonSales(from, to).forEach((m, v) -> out.merge(m, v, Double::sum));
		return out;
	}

	public Map<YearMonth, Double> monthlyExpenses(LocalDate from, LocalDate to)
	{
		return monthly("SELECT substr(expense_date, 1, 7) AS ym, COALESCE(SUM(amount), 0) AS total "
				+ "FROM expense WHERE " + ExpenseSql.COUNTS + " AND expense_date BETWEEN ? AND ? GROUP BY ym", from, to);
	}

	/** The departments and drinks, then Tiki Taka and the Salon as two more lines. */
	public List<NamedAmount> incomeByActivity(LocalDate from, LocalDate to)
	{
		String columns = ACTIVITIES.stream()
				.map(a -> "COALESCE(SUM(" + a.column() + "), 0)")
				.collect(Collectors.joining(", "));
		List<NamedAmount> out = jdbc.queryForObject("SELECT " + columns + " FROM daily_summary WHERE log_date BETWEEN ? AND ?",
				(rs, n) -> {
					List<NamedAmount> lines = new ArrayList<>();
					for (int i = 0; i < ACTIVITIES.size(); i++)
					{
						lines.add(new NamedAmount(ACTIVITIES.get(i).name(), rs.getDouble(i + 1)));
					}
					return lines;
				}, iso(from), iso(to));
		out.add(new NamedAmount(RESTAURANT, restaurantSales(from, to)));
		out.add(new NamedAmount(SALON, salonSales(from, to)));
		return out;
	}

	/**
	 * Lines of paymentMix, in order. The restaurant and the Salon keep their own cash and card
	 * lines: the restaurant's cash is in its own till, and card sales go straight to the bank.
	 */
	public static final List<String> PAYMENT_LINES = List.of("Cash", "Card", "Cheque", "Drinks (bar)",
			"Tiki Taka cash", "Tiki Taka card", "Salon cash", "Salon card");

	public List<NamedAmount> paymentMix(LocalDate from, LocalDate to)
	{
		// The file's cash/card/cheque split covers the departments; drinks are listed on their own.
		double[] v = jdbc.queryForObject("""
				SELECT COALESCE(SUM(total_cash), 0), COALESCE(SUM(total_card), 0), COALESCE(SUM(total_cheque), 0),
				       COALESCE(SUM(drinks_amount_total), 0)
				FROM daily_summary WHERE log_date BETWEEN ? AND ?
				""", (rs, n) -> new double[]{rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)}, iso(from), iso(to));
		double[] r = cashAndCard("restaurant_sales", from, to);
		double[] sa = cashAndCard("salon_sales", from, to);
		return lines(PAYMENT_LINES, v[0], v[1], v[2], v[3], r[0], r[1], sa[0], sa[1]);
	}

	/** table is one of the two sales tables — a constant, never user input. */
	private double[] cashAndCard(String table, LocalDate from, LocalDate to)
	{
		return jdbc.queryForObject("SELECT COALESCE(SUM(cash), 0), COALESCE(SUM(card), 0) FROM " + table + " WHERE sale_date BETWEEN ? AND ?",
				(rs, n) -> new double[]{rs.getDouble(1), rs.getDouble(2)}, iso(from), iso(to));
	}

	private static List<NamedAmount> lines(List<String> names, double... values)
	{
		List<NamedAmount> out = new ArrayList<>();
		for (int i = 0; i < names.size(); i++)
		{
			out.add(new NamedAmount(names.get(i), values[i]));
		}
		return out;
	}

	/** Income per department and month, every department present for every month with a log. */
	public Map<YearMonth, List<NamedAmount>> monthlyIncomeByActivity(LocalDate from, LocalDate to)
	{
		String columns = ACTIVITIES.stream()
				.map(a -> "COALESCE(SUM(" + a.column() + "), 0)")
				.collect(Collectors.joining(", "));
		Map<YearMonth, List<NamedAmount>> out = new LinkedHashMap<>();
		jdbc.query("SELECT substr(log_date, 1, 7) AS ym, " + columns + " FROM daily_summary WHERE log_date BETWEEN ? AND ? GROUP BY ym ORDER BY ym",
				rs -> {
					List<NamedAmount> month = new ArrayList<>();
					for (int i = 0; i < ACTIVITIES.size(); i++)
					{
						month.add(new NamedAmount(ACTIVITIES.get(i).name(), rs.getDouble(i + 2)));
					}
					out.put(YearMonth.parse(rs.getString("ym")), month);
				}, iso(from), iso(to));
		return out;
	}

	/** The same lines as paymentMix, per month. */
	public Map<YearMonth, List<NamedAmount>> monthlyPaymentMix(LocalDate from, LocalDate to)
	{
		Map<YearMonth, double[]> values = new LinkedHashMap<>();
		jdbc.query("""
				SELECT substr(log_date, 1, 7) AS ym, COALESCE(SUM(total_cash), 0), COALESCE(SUM(total_card), 0),
				       COALESCE(SUM(total_cheque), 0), COALESCE(SUM(drinks_amount_total), 0)
				FROM daily_summary WHERE log_date BETWEEN ? AND ? GROUP BY ym ORDER BY ym
				""", rs -> {
			double[] v = values.computeIfAbsent(YearMonth.parse(rs.getString(1)), k -> new double[8]);
			for (int i = 0; i < 4; i++)
			{
				v[i] = rs.getDouble(i + 2);
			}
		}, iso(from), iso(to));
		int slot = 4;
		for (String table : List.of("restaurant_sales", "salon_sales"))
		{
			final int at = slot;
			jdbc.query("SELECT substr(sale_date, 1, 7) AS ym, COALESCE(SUM(cash), 0), COALESCE(SUM(card), 0) FROM " + table
					+ " WHERE sale_date BETWEEN ? AND ? GROUP BY ym ORDER BY ym", rs -> {
				double[] v = values.computeIfAbsent(YearMonth.parse(rs.getString(1)), k -> new double[8]);
				v[at] = rs.getDouble(2);
				v[at + 1] = rs.getDouble(3);
			}, iso(from), iso(to));
			slot += 2;
		}
		Map<YearMonth, List<NamedAmount>> out = new LinkedHashMap<>();
		values.forEach((m, v) -> out.put(m, lines(PAYMENT_LINES, v)));
		return out;
	}

	/** Tiki Taka's sales (cash + card): the restaurant's own revenue, entered on its page rather than in the daily log. */
	public double restaurantSales(LocalDate from, LocalDate to)
	{
		return sum("SELECT COALESCE(SUM(cash + card), 0) FROM restaurant_sales WHERE sale_date BETWEEN ? AND ?", from, to);
	}

	public Map<LocalDate, Double> dailyRestaurantSales(LocalDate from, LocalDate to)
	{
		return dailySales("restaurant_sales", from, to);
	}

	/** The Salon's sales (cash + card), entered by the reception on the Salon page. */
	public double salonSales(LocalDate from, LocalDate to)
	{
		return sum("SELECT COALESCE(SUM(cash + card), 0) FROM salon_sales WHERE sale_date BETWEEN ? AND ?", from, to);
	}

	public Map<YearMonth, Double> monthlySalonSales(LocalDate from, LocalDate to)
	{
		return monthly("SELECT substr(sale_date, 1, 7) AS ym, COALESCE(SUM(cash + card), 0) AS total "
				+ "FROM salon_sales WHERE sale_date BETWEEN ? AND ? GROUP BY ym", from, to);
	}

	public Map<LocalDate, Double> dailySalonSales(LocalDate from, LocalDate to)
	{
		return dailySales("salon_sales", from, to);
	}

	private Map<LocalDate, Double> dailySales(String table, LocalDate from, LocalDate to)
	{
		Map<LocalDate, Double> out = new LinkedHashMap<>();
		jdbc.query("SELECT sale_date, cash + card FROM " + table + " WHERE sale_date BETWEEN ? AND ? ORDER BY sale_date",
				rs -> {
					out.put(LocalDate.parse(rs.getString(1)), rs.getDouble(2));
				}, iso(from), iso(to));
		return out;
	}

	public Map<YearMonth, Double> monthlyRestaurantSales(LocalDate from, LocalDate to)
	{
		return monthly("SELECT substr(sale_date, 1, 7) AS ym, COALESCE(SUM(cash + card), 0) AS total "
				+ "FROM restaurant_sales WHERE sale_date BETWEEN ? AND ? GROUP BY ym", from, to);
	}

	public List<NamedAmount> topExpenseCategories(LocalDate from, LocalDate to, int limit)
	{
		return jdbc.query("SELECT category, SUM(amount) AS total FROM expense WHERE " + ExpenseSql.COUNTS
						+ " AND expense_date BETWEEN ? AND ? GROUP BY category ORDER BY total DESC LIMIT ?",
				(rs, n) -> new NamedAmount(rs.getString("category"), rs.getDouble("total")), iso(from), iso(to), limit);
	}

	/* ======================= daily log coverage ======================= */

	public Set<LocalDate> loggedDates(LocalDate from, LocalDate to)
	{
		List<String> rows = jdbc.queryForList("SELECT log_date FROM daily_summary WHERE log_date BETWEEN ? AND ?",
				String.class, iso(from), iso(to));
		Set<LocalDate> out = new HashSet<>();
		for (String d : rows)
		{
			out.add(LocalDate.parse(d));
		}
		return out;
	}

	public Map<LocalDate, Double> dailyTotals(LocalDate from, LocalDate to)
	{
		Map<LocalDate, Double> out = new LinkedHashMap<>();
		jdbc.query("SELECT log_date, " + IncomeSql.DAY_INCOME + " FROM daily_summary WHERE log_date BETWEEN ? AND ? ORDER BY log_date",
				rs -> {
					out.put(LocalDate.parse(rs.getString(1)), rs.getDouble(2));
				}, iso(from), iso(to));
		return out;
	}

	public Optional<LocalDate> firstLogDate()
	{
		String first = jdbc.queryForObject("SELECT MIN(log_date) FROM daily_summary", String.class);
		return Optional.ofNullable(first).map(LocalDate::parse);
	}

	public Optional<LocalDate> firstExpenseDate()
	{
		String first = jdbc.queryForObject("SELECT MIN(expense_date) FROM expense WHERE " + ExpenseSql.COUNTS, String.class);
		return Optional.ofNullable(first).map(LocalDate::parse);
	}

	/** The most recent day with an imported log file. */
	public Optional<DaySummary> latestDay()
	{
		String columns = ACTIVITIES.stream().map(Activity::column).collect(Collectors.joining(", "));
		List<DaySummary> rows = jdbc.query(
				"SELECT log_date, total_ttc, total_cash, total_card, total_cheque, " + columns
						+ " FROM daily_summary ORDER BY log_date DESC LIMIT 1",
				(rs, n) -> {
					List<NamedAmount> activities = new ArrayList<>();
					for (Activity a : ACTIVITIES)
					{
						activities.add(new NamedAmount(a.name(), rs.getDouble(a.column())));
					}
					double income = rs.getDouble("total_ttc") + rs.getDouble("drinks_amount_total");
					return new DaySummary(LocalDate.parse(rs.getString("log_date")), income,
							rs.getDouble("total_cash"), rs.getDouble("total_card"), rs.getDouble("total_cheque"),
							activities);
				});
		return rows.stream().findFirst();
	}

	/** Expenses dated after today — almost always a typo in the year. */
	public List<FutureExpense> futureExpenses(LocalDate today)
	{
		return jdbc.query("SELECT expense_date, category, description, amount FROM expense WHERE "
						+ ExpenseSql.COUNTS + " AND expense_date > ? ORDER BY expense_date",
				(rs, n) -> new FutureExpense(LocalDate.parse(rs.getString(1)), rs.getString(2),
						rs.getString(3), rs.getDouble(4)), iso(today));
	}

	/* ======================= reception balance & safe ======================= */

	/** Year mode shared with the desktop app: RESET restarts the reception balance every 1 January. */
	public boolean resetEachYear()
	{
		List<String> values = jdbc.queryForList("SELECT value FROM app_setting WHERE key = 'storage.year_mode'", String.class);
		return !values.isEmpty() && "RESET".equalsIgnoreCase(values.get(0));
	}

	/** Changed from the Administration page; the desktop app reads the same setting. */
	public void setResetEachYear(boolean reset)
	{
		jdbc.update("INSERT INTO app_setting (key, value) VALUES ('storage.year_mode', ?) "
				+ "ON CONFLICT(key) DO UPDATE SET value = excluded.value", reset ? "RESET" : "CARRY");
	}

	public ReceptionBalance receptionBalance(LocalDate today)
	{
		boolean reset = resetEachYear();
		String from = reset ? iso(today.withDayOfYear(1)) : ALL_TIME;
		String scope = reset ? "since 1 Jan " + today.getYear() : "all time";
		return new ReceptionBalance(scope,
				// Drinks are sold at the bar for cash, so they end up at the reception too.
				sum("SELECT COALESCE(SUM(" + IncomeSql.DAY_INCOME + "), 0) FROM daily_summary WHERE log_date >= ?", from),
				// The Salon's cash is handed to the reception; its card sales go to the bank.
				sum("SELECT COALESCE(SUM(cash), 0) FROM salon_sales WHERE sale_date >= ?", from),
				sum("SELECT COALESCE(SUM(amount), 0) FROM cash_movement WHERE till = 'RECEPTION' AND type = 'DEPOSIT' AND movement_date >= ?", from),
				sum("SELECT COALESCE(SUM(amount), 0) FROM cash_movement WHERE till = 'RECEPTION' AND type NOT IN ('DEPOSIT', 'BANK') AND movement_date >= ?", from),
				sum("SELECT COALESCE(SUM(amount), 0) FROM cash_movement WHERE till = 'RECEPTION' AND type = 'BANK' AND movement_date >= ?", from),
				sum("SELECT COALESCE(SUM(amount), 0) FROM expense WHERE " + ExpenseSql.COUNTS + " AND "
						+ ExpenseSql.PAID_FROM + " = 'RECEPTION' AND expense_date >= ?", from),
				sum("SELECT COALESCE(SUM(amount), 0) FROM safe_movement WHERE type = 'IN' AND source = 'RECEPTION' AND movement_date >= ?", from));
	}

	/** The restaurant's till, same year scope as the reception. */
	public RestaurantBalance restaurantBalance(LocalDate today)
	{
		boolean reset = resetEachYear();
		String from = reset ? iso(today.withDayOfYear(1)) : ALL_TIME;
		String scope = reset ? "since 1 Jan " + today.getYear() : "all time";
		return new RestaurantBalance(scope,
				sum("SELECT COALESCE(SUM(cash), 0) FROM restaurant_sales WHERE sale_date >= ?", from),
				sum("SELECT COALESCE(SUM(card), 0) FROM restaurant_sales WHERE sale_date >= ?", from),
				sum("SELECT COALESCE(SUM(amount), 0) FROM cash_movement WHERE till = 'RESTAURANT' AND type = 'DEPOSIT' AND movement_date >= ?", from),
				sum("SELECT COALESCE(SUM(amount), 0) FROM cash_movement WHERE till = 'RESTAURANT' AND type NOT IN ('DEPOSIT', 'BANK') AND movement_date >= ?", from),
				sum("SELECT COALESCE(SUM(amount), 0) FROM cash_movement WHERE till = 'RESTAURANT' AND type = 'BANK' AND movement_date >= ?", from),
				sum("SELECT COALESCE(SUM(amount), 0) FROM expense WHERE " + ExpenseSql.COUNTS + " AND "
						+ ExpenseSql.PAID_FROM + " = 'RESTAURANT' AND expense_date >= ?", from),
				sum("SELECT COALESCE(SUM(amount), 0) FROM safe_movement WHERE type = 'IN' AND source = 'RESTAURANT' AND movement_date >= ?", from));
	}

	public SafeBalance safeBalance()
	{
		return new SafeBalance(
				sum("SELECT COALESCE(SUM(amount), 0) FROM safe_movement WHERE type IN " + SAFE_IN_TYPES),
				sum("SELECT COALESCE(SUM(amount), 0) FROM safe_movement WHERE type = 'BANK'"),
				sum("SELECT COALESCE(SUM(amount), 0) FROM safe_movement WHERE type NOT IN ('IN', 'ADJUST_IN', 'BANK')"),
				sum("SELECT COALESCE(SUM(amount), 0) FROM expense WHERE " + ExpenseSql.COUNTS + " AND "
						+ ExpenseSql.PAID_FROM + " = 'SAFE'"));
	}

	/* ======================= bank accounts ======================= */

	/**
	 * The Association's account takes the daily log's card and cheque payments, the Salon's card
	 * payments, cash from the reception and the safe, and pays expenses marked BANK. Tiki Taka's
	 * takes the restaurant's card sales and the cash taken from its till, and pays RESTAURANT_BANK.
	 */
	public BankBalance bankBalance(BankAccount account)
	{
		boolean assoc = account == BankAccount.ASSOCIATION;
		double cardAndCheque = assoc
				? sum("SELECT COALESCE(SUM(COALESCE(total_card, 0) + COALESCE(total_cheque, 0)), 0) FROM daily_summary")
				+ sum("SELECT COALESCE(SUM(card), 0) FROM salon_sales")
				: sum("SELECT COALESCE(SUM(card), 0) FROM restaurant_sales");
		double cashDeposited = sum("SELECT COALESCE(SUM(amount), 0) FROM cash_movement WHERE type = 'BANK' AND till = ?",
				assoc ? "RECEPTION" : "RESTAURANT");
		double fromSafe = assoc ? sum("SELECT COALESCE(SUM(amount), 0) FROM safe_movement WHERE type = 'BANK'") : 0;
		double otherIn = sum("SELECT COALESCE(SUM(amount), 0) FROM bank_movement WHERE account = ? AND type IN ('IN', 'TRANSFER_IN')", account.name());
		double otherOut = sum("SELECT COALESCE(SUM(amount), 0) FROM bank_movement WHERE account = ? AND type IN ('OUT', 'TRANSFER_OUT')", account.name());
		double corrections = sum("SELECT COALESCE(SUM(CASE WHEN type = 'ADJUST_IN' THEN amount ELSE -amount END), 0) FROM bank_movement"
				+ " WHERE account = ? AND type IN ('ADJUST_IN', 'ADJUST_OUT')", account.name());
		double expensesPaid = sum("SELECT COALESCE(SUM(amount), 0) FROM expense WHERE " + ExpenseSql.COUNTS + " AND "
				+ ExpenseSql.PAID_FROM + " = ?", assoc ? "BANK" : "RESTAURANT_BANK");
		return new BankBalance(account, cardAndCheque, cashDeposited, fromSafe, otherIn, otherOut, expensesPaid, corrections);
	}

	public List<Movement> recentBankMovements(BankAccount account, int limit)
	{
		return jdbc.query("""
				SELECT id, movement_date, type, amount, note FROM bank_movement
				WHERE account = ? ORDER BY movement_date DESC, id DESC LIMIT ?
				""", (rs, n) -> {
			String type = rs.getString("type");
			String label = switch (type)
			{
				case "IN" -> "Money in";
				case "OUT" -> "Payment";
				case "TRANSFER_IN" -> "Transfer from " + (account.other() == BankAccount.RESTAURANT ? "Tiki Taka" : "the Association");
				case "TRANSFER_OUT" -> "Transfer to " + (account.other() == BankAccount.RESTAURANT ? "Tiki Taka" : "the Association");
				case "ADJUST_IN" -> "Statement correction (+)";
				default -> "Statement correction (−)";
			};
			boolean incoming = type.equals("IN") || type.equals("TRANSFER_IN") || type.equals("ADJUST_IN");
			return new Movement(rs.getLong("id"), "BANK", LocalDate.parse(rs.getString("movement_date")), label,
					rs.getDouble("amount"), incoming, rs.getString("note"));
		}, account.name(), limit);
	}

	/** Everything sent to the bank since a date, from the reception and from the safe. */
	public double sentToBankSince(LocalDate from)
	{
		return sum("SELECT COALESCE(SUM(amount), 0) FROM cash_movement WHERE type = 'BANK' AND movement_date >= ?", iso(from))
				+ sum("SELECT COALESCE(SUM(amount), 0) FROM safe_movement WHERE type = 'BANK' AND movement_date >= ?", iso(from));
	}

	public List<Movement> recentReceptionMovements(int limit)
	{
		return recentTillMovements("RECEPTION", limit);
	}

	public List<Movement> recentRestaurantMovements(int limit)
	{
		return recentTillMovements("RESTAURANT", limit);
	}

	private List<Movement> recentTillMovements(String till, int limit)
	{
		return jdbc.query("""
				SELECT id, movement_date, type, amount, note FROM cash_movement
				WHERE till = ? ORDER BY movement_date DESC, id DESC LIMIT ?
				""", (rs, n) -> {
			String type = rs.getString("type");
			String label = switch (type)
			{
				case "DEPOSIT" -> "Deposit";
				case "BANK" -> "Sent to bank";
				default -> "Withdrawal";
			};
			return new Movement(rs.getLong("id"), "RECEPTION", LocalDate.parse(rs.getString("movement_date")), label,
					rs.getDouble("amount"), "DEPOSIT".equals(type), rs.getString("note"));
		}, till, limit);
	}

	public List<Movement> recentSafeMovements(int limit)
	{
		return jdbc.query("""
				SELECT id, movement_date, type, amount, note, source FROM safe_movement
				ORDER BY movement_date DESC, id DESC LIMIT ?
				""", (rs, n) -> {
			String type = rs.getString("type");
			String label = switch (type)
			{
				case "IN" -> "RESTAURANT".equals(rs.getString("source")) ? "From restaurant" : "From reception";
				case "BANK" -> "Sent to bank";
				case "ADJUST_IN" -> "Count correction (+)";
				case "ADJUST_OUT" -> "Count correction (−)";
				default -> "Payment";
			};
			boolean incoming = "IN".equals(type) || "ADJUST_IN".equals(type);
			return new Movement(rs.getLong("id"), "SAFE", LocalDate.parse(rs.getString("movement_date")), label,
					rs.getDouble("amount"), incoming, rs.getString("note"));
		}, limit);
	}

	/* ======================= helpers ======================= */

	private Map<YearMonth, Double> monthly(String sql, LocalDate from, LocalDate to)
	{
		Map<YearMonth, Double> out = new LinkedHashMap<>();
		jdbc.query(sql, rs -> {
			out.put(YearMonth.parse(rs.getString("ym")), rs.getDouble("total"));
		}, iso(from), iso(to));
		return out;
	}

	private double sum(String sql, Object... args)
	{
		Object[] converted = new Object[args.length];
		for (int i = 0; i < args.length; i++)
		{
			converted[i] = args[i] instanceof LocalDate d ? iso(d) : args[i];
		}
		Double value = jdbc.queryForObject(sql, Double.class, converted);
		return value == null ? 0.0 : value;
	}

	private static String iso(LocalDate date)
	{
		return date.toString();
	}
}
