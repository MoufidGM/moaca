package com.cslsm.repo;

import com.cslsm.model.DailySummary;
import com.cslsm.util.AppConfig;

import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DailyRepo
{

	// === Add near the top of DailyRepo.java (class level) ===
	public static final java.util.Map<String, String> FIELD_BY_ACTIVITY = java.util.Map.ofEntries(java.util.Map.entry("Terrain", "total_terrain"), java.util.Map.entry("Padel", "total_padel"), java.util.Map.entry("Gym", "total_gym"), java.util.Map.entry("Park", "total_park"), java.util.Map.entry("Mini Golf", "total_mini_golf"), java.util.Map.entry("Ping Pong", "total_ping_pong"), java.util.Map.entry("Academy", "total_academy_foot"), java.util.Map.entry("Taekwondo", "total_taekwondo"), java.util.Map.entry("Shoes", "total_shoes"), java.util.Map.entry("Drinks", "drinks_amount_total"), java.util.Map.entry("Daily Total", "total_ttc"));
	private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
	private final String jdbcUrl;

	public DailyRepo(String dbPath)
	{
		this.jdbcUrl = "jdbc:sqlite:" + dbPath + "?busy_timeout=5000";
	}

	/* ======================= Public API ======================= */

	private static String toIso(LocalDate d)
	{
		return d == null ? null : d.format(ISO);
	}

	private static LocalDate fromIso(String s)
	{
		return (s == null || s.isBlank()) ? null : LocalDate.parse(s, ISO);
	}

	private static DailySummary mapRow(ResultSet rs) throws SQLException
	{
		DailySummary d = new DailySummary();
		d.setId(rs.getLong("id"));
		d.setLogDate(rs.getString("log_date")); // convert String -> LocalDate

		d.setTotalTerrain((Double) rs.getObject("total_terrain"));
		d.setTotalPadel((Double) rs.getObject("total_padel"));
		d.setTotalGym((Double) rs.getObject("total_gym"));
		d.setTotalPark((Double) rs.getObject("total_park"));
		d.setTotalMiniGolf((Double) rs.getObject("total_mini_golf"));
		d.setTotalPingPong((Double) rs.getObject("total_ping_pong"));
		d.setTotalAcademyFoot((Double) rs.getObject("total_academy_foot"));
		d.setTotalTaekwondo((Double) rs.getObject("total_taekwondo"));
		d.setTotalShoes((Double) rs.getObject("total_shoes"));

		d.setTotalTtc((Double) rs.getObject("total_ttc"));
		d.setTotalHt((Double) rs.getObject("total_ht"));
		d.setTotalCash((Double) rs.getObject("total_cash"));
		d.setTotalCard((Double) rs.getObject("total_card"));
		d.setTotalCheque((Double) rs.getObject("total_cheque"));

		d.setFilePath(rs.getString("file_path"));
		d.setDrinksQtyTotal((Integer) rs.getObject("drinks_qty_total"));
		d.setDrinksAmountTotal((Double) rs.getObject("drinks_amount_total"));
		return d;
	}

	public Optional<DailySummary> findByDate(LocalDate date) throws SQLException
	{
		final String sql = "SELECT * FROM daily_summary WHERE log_date = ?";
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement(sql))
		{
			ps.setString(1, toIso(date));
			try (ResultSet rs = ps.executeQuery())
			{
				if (rs.next()) return Optional.of(mapRow(rs));
				return Optional.empty();
			}
		}
	}

	/**
	 * Upsert whole daily summary using UNIQUE(log_date).
	 */
	public void upsertSummary(DailySummary s) throws SQLException
	{
		final String sql = """
				INSERT INTO daily_summary
				    (log_date, total_terrain, total_padel, total_gym, total_park, total_mini_golf,
				     total_ping_pong, total_academy_foot, total_taekwondo, total_shoes,
				     total_ttc, total_ht, total_cash, total_card, total_cheque,
				     file_path, drinks_qty_total, drinks_amount_total)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT(log_date) DO UPDATE SET
				    total_terrain=excluded.total_terrain,
				    total_padel=excluded.total_padel,
				    total_gym=excluded.total_gym,
				    total_park=excluded.total_park,
				    total_mini_golf=excluded.total_mini_golf,
				    total_ping_pong=excluded.total_ping_pong,
				    total_academy_foot=excluded.total_academy_foot,
				    total_taekwondo=excluded.total_taekwondo,
				    total_shoes=excluded.total_shoes,
				    total_ttc=excluded.total_ttc,
				    total_ht=excluded.total_ht,
				    total_cash=excluded.total_cash,
				    total_card=excluded.total_card,
				    total_cheque=excluded.total_cheque,
				    file_path=excluded.file_path,
				    drinks_qty_total=excluded.drinks_qty_total,
				    drinks_amount_total=excluded.drinks_amount_total
				""";
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement(sql))
		{
			int i = 1;
			ps.setString(i++, s.getLogDate());           // store ISO text
			ps.setObject(i++, s.getTotalTerrain());
			ps.setObject(i++, s.getTotalPadel());
			ps.setObject(i++, s.getTotalGym());
			ps.setObject(i++, s.getTotalPark());
			ps.setObject(i++, s.getTotalMiniGolf());
			ps.setObject(i++, s.getTotalPingPong());
			ps.setObject(i++, s.getTotalAcademyFoot());
			ps.setObject(i++, s.getTotalTaekwondo());
			ps.setObject(i++, s.getTotalShoes());
			ps.setObject(i++, s.getTotalTtc());
			ps.setObject(i++, s.getTotalHt());
			ps.setObject(i++, s.getTotalCash());
			ps.setObject(i++, s.getTotalCard());
			ps.setObject(i++, s.getTotalCheque());
			ps.setString(i++, s.getFilePath());
			ps.setObject(i++, s.getDrinksQtyTotal());
			ps.setObject(i++, s.getDrinksAmountTotal());
			ps.executeUpdate();
		}
	}

	public void deleteByDate(LocalDate date) throws SQLException
	{
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement("DELETE FROM daily_summary WHERE log_date = ?"))
		{
			ps.setString(1, toIso(date));
			ps.executeUpdate();
		}
	}

	/* ======================= Internal helpers ======================= */

	/**
	 * Returns (date -> numeric value) for the given DB column between the two dates inclusive.
	 */
	public java.util.Map<java.time.LocalDate, Double> activitySeries(String dbColumn, java.time.LocalDate from, java.time.LocalDate to)
	{
		String url = "jdbc:sqlite:" + com.cslsm.util.AppConfig.getDbPath();
		String sql = "SELECT log_date, " + dbColumn + " FROM daily_summary WHERE log_date BETWEEN ? AND ? ORDER BY log_date";
		java.util.Map<java.time.LocalDate, Double> out = new java.util.LinkedHashMap<>();
		try (Connection conn = DriverManager.getConnection(url);
			 PreparedStatement ps = conn.prepareStatement(sql))
		{
			ps.setString(1, from.toString());
			ps.setString(2, to.toString());
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					java.time.LocalDate d = java.time.LocalDate.parse(rs.getString(1));
					out.put(d, rs.getDouble(2));
				}
			}
		}
		catch (Exception ignored)
		{
		}
		return out;
	}

	/**
	 * Sum all categories for a given YearMonth.
	 */
	public java.util.Map<String, Double> monthTotals(java.time.YearMonth ym)
	{
		java.time.LocalDate start = ym.atDay(1);
		java.time.LocalDate end = ym.atEndOfMonth();
		String url = "jdbc:sqlite:" + com.cslsm.util.AppConfig.getDbPath();
		String sql = """
				SELECT
				  COALESCE(SUM(total_terrain),0),
				  COALESCE(SUM(total_padel),0),
				  COALESCE(SUM(total_gym),0),
				  COALESCE(SUM(total_park),0),
				  COALESCE(SUM(total_mini_golf),0),
				  COALESCE(SUM(total_ping_pong),0),
				  COALESCE(SUM(total_academy_foot),0),
				  COALESCE(SUM(total_taekwondo),0),
				  COALESCE(SUM(total_shoes),0),
				  COALESCE(SUM(drinks_amount_total),0),
				  COALESCE(SUM(total_cash),0),
				  COALESCE(SUM(total_card),0),
  				  COALESCE(SUM(total_Cheque),0),
				  COALESCE(SUM(total_ttc),0)
				FROM daily_summary
				WHERE log_date BETWEEN ? AND ?
				""";
		java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
		try (Connection conn = DriverManager.getConnection(url);
			 PreparedStatement ps = conn.prepareStatement(sql))
		{
			ps.setString(1, start.toString());
			ps.setString(2, end.toString());
			try (ResultSet rs = ps.executeQuery())
			{
				if (rs.next())
				{
					out.put("Terrain", rs.getDouble(1));
					out.put("Padel", rs.getDouble(2));
					out.put("Gym", rs.getDouble(3));
					out.put("Park", rs.getDouble(4));
					out.put("Mini Golf", rs.getDouble(5));
					out.put("Ping Pong", rs.getDouble(6));
					out.put("Academy", rs.getDouble(7));
					out.put("Taekwondo", rs.getDouble(8));
					out.put("Shoes", rs.getDouble(9));
					out.put("Drinks", rs.getDouble(10));
					out.put("Cash", rs.getDouble(11));
					out.put("Card", rs.getDouble(12));
					out.put("Cheque",  rs.getDouble(13));
					out.put("Total",  rs.getDouble(14));
					}
			}
		}
		catch (Exception ignored)
		{
			System.out.println("monthTotals...: " +ignored.getMessage() );
		}
		return out;
	}

	/**
	 * Fetch all summaries between inclusive date bounds, ordered by date.
	 */
	public List<DailySummary> summariesBetween(LocalDate start, LocalDate end) throws SQLException
	{
		final String sql = """
				SELECT *
				FROM daily_summary
				WHERE log_date BETWEEN ? AND ?
				ORDER BY log_date
				""";
		List<DailySummary> out = new ArrayList<>();
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement(sql))
		{
			ps.setString(1, toIso(start));
			ps.setString(2, toIso(end));
			try (ResultSet rs = ps.executeQuery())
			{
              while (rs.next())
              {
                out.add(mapRow(rs));
              }
			}
		}
		return out;
	}

	/**
	 * Back-compat for UI: DailyView calls summaryFor(date).
	 */
	public Optional<DailySummary> summaryFor(LocalDate date) throws SQLException
	{
		return findByDate(date);
	}

	/**
	 * Overload if some callers pass ISO strings.
	 */
	public Optional<DailySummary> summaryFor(String isoDate) throws SQLException
	{
		return findByDate(LocalDate.parse(isoDate)); // expects yyyy-MM-dd
	}

	public List<DailySummary> listBetween(LocalDate from, LocalDate to)
	{
		String sql = """
				    SELECT log_date, file_path,
				           total_terrain, total_padel, total_gym, total_park,
				           total_mini_golf, total_ping_pong, total_academy_foot,
				           total_taekwondo, total_shoes, total_ttc, drinks_amount_total,
				           total_cash, total_card, total_cheque
				    FROM daily_summary
				    WHERE log_date >= ? AND log_date <= ?
				    ORDER BY log_date
				""";

		List<DailySummary> out = new ArrayList<>();
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + AppConfig.getDbPath());
			 PreparedStatement ps = c.prepareStatement(sql))
		{

			ps.setString(1, from.toString());
			ps.setString(2, to.toString());

			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					DailySummary s = new DailySummary();
					s.setLogDate(rs.getString("log_date"));
					s.setFilePath(rs.getString("file_path"));
					s.setTotalTerrain(rs.getDouble("total_terrain"));
					s.setTotalPadel(rs.getDouble("total_padel"));
					s.setTotalGym(rs.getDouble("total_gym"));
					s.setTotalPark(rs.getDouble("total_park"));
					s.setTotalMiniGolf(rs.getDouble("total_mini_golf"));
					s.setTotalPingPong(rs.getDouble("total_ping_pong"));
					s.setTotalAcademyFoot(rs.getDouble("total_academy_foot"));
					s.setTotalTaekwondo(rs.getDouble("total_taekwondo"));
					s.setTotalShoes(rs.getDouble("total_shoes"));
					s.setTotalTtc(rs.getDouble("total_ttc"));
					s.setDrinksAmountTotal(rs.getDouble("drinks_amount_total"));
					s.setTotalCash(rs.getDouble("total_cash"));
					s.setTotalCard(rs.getDouble("total_card"));
					s.setTotalCheque(rs.getDouble("total_cheque"));
					out.add(s);
				}
			}
		}
		catch (SQLException e)
		{
			throw new RuntimeException("listBetween failed: " + e.getMessage(), e);
		}
		return out;
	}
}
