package com.cslsm.repo;

import com.cslsm.model.CashMovement;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class CashRepo
{

	private final String jdbcUrl;

	public CashRepo(String dbPath)
	{
		this.jdbcUrl = "jdbc:sqlite:" + dbPath + "?busy_timeout=5000";
	}

	private static CashMovement mapRow(ResultSet rs) throws SQLException
	{
		CashMovement m = new CashMovement();
		m.setId(rs.getLong("id"));
		m.setMovementDate(rs.getString("movement_date"));
		m.setType(rs.getString("type"));
		m.setAmount((Double) rs.getObject("amount"));
		m.setNote(rs.getString("note"));
		return m;
	}

	public void insert(CashMovement m) throws SQLException
	{
		final String sql = """
				INSERT INTO cash_movement (movement_date, type, amount, note)
				VALUES (?, ?, ?, ?)
				""";
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement(sql))
		{
			int i = 1;
			ps.setString(i++, m.getMovementDate());
			ps.setString(i++, m.getType());
			ps.setObject(i++, m.getAmount());
			ps.setString(i++, m.getNote());
			ps.executeUpdate();
		}
	}

	public void delete(long id) throws SQLException
	{
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement("DELETE FROM cash_movement WHERE id = ?"))
		{
			ps.setLong(1, id);
			ps.executeUpdate();
		}
	}

	/** True when the storage restarts from 0 every January 1st (setting 'storage.year_mode' = RESET). */
	public boolean resetEachYear()
	{
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement("SELECT value FROM app_setting WHERE key = 'storage.year_mode'");
			 ResultSet rs = ps.executeQuery())
		{
			if (rs.next()) return "RESET".equalsIgnoreCase(rs.getString(1));
		}
		catch (SQLException e)
		{
			e.printStackTrace();
		}
		return false;
	}

	/**
	 * Storage balance (money at the reception) — a global running cash position:
	 *   all income (daily_summary.total_ttc)
	 *   + manual deposits
	 *   - withdrawals and bank transfers
	 *   - expenses paid from the storage
	 *   - transfers into the safe.
	 * Scope: all time (CARRY mode), or since January 1st of the current year (RESET mode).
	 */
	public double balance()
	{
		String from = resetEachYear()
				? java.time.LocalDate.now().withDayOfYear(1).toString()
				: "0000-01-01";
		final String sql = """
				SELECT
				  (SELECT COALESCE(SUM(total_ttc), 0) FROM daily_summary WHERE log_date >= ?)
				  +
				  (SELECT COALESCE(SUM(CASE WHEN type = 'DEPOSIT' THEN amount ELSE -amount END), 0) FROM cash_movement WHERE movement_date >= ?)
				  -
				  (SELECT COALESCE(SUM(amount), 0) FROM expense WHERE paid_from_storage = 1 AND expense_date >= ?)
				  -
				  (SELECT COALESCE(SUM(amount), 0) FROM safe_movement WHERE type = 'IN' AND movement_date >= ?)
				""";
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement(sql))
		{
			ps.setString(1, from);
			ps.setString(2, from);
			ps.setString(3, from);
			ps.setString(4, from);
			try (ResultSet rs = ps.executeQuery())
			{
				if (rs.next()) return rs.getDouble(1);
			}
		}
		catch (SQLException e)
		{
			e.printStackTrace();
		}
		return 0.0;
	}

	/** Total transferred from storage to the bank, all time. */
	public double totalToBank()
	{
		final String sql = "SELECT COALESCE(SUM(amount),0) FROM cash_movement WHERE type = 'BANK'";
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement(sql);
			 ResultSet rs = ps.executeQuery())
		{
			if (rs.next()) return rs.getDouble(1);
		}
		catch (SQLException e)
		{
			e.printStackTrace();
		}
		return 0.0;
	}

	/** Most recent movements, newest first. */
	public List<CashMovement> listRecent(int limit) throws SQLException
	{
		final String sql = """
				SELECT * FROM cash_movement
				ORDER BY movement_date DESC, id DESC
				LIMIT ?
				""";
		List<CashMovement> out = new ArrayList<>();
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement(sql))
		{
			ps.setInt(1, limit);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next()) out.add(mapRow(rs));
			}
		}
		return out;
	}
}
