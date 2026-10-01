package com.cslsm.repo;

import com.cslsm.model.CashMovement;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Physical safe. IN = received from the storage, BANK = sent to the bank,
 * OUT = other payment taken from the safe.
 */
public class SafeRepo
{

	public static final String IN = "IN";
	public static final String BANK = "BANK";
	public static final String OUT = "OUT";

	private final String jdbcUrl;

	public SafeRepo(String dbPath)
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
				INSERT INTO safe_movement (movement_date, type, amount, note)
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
			 PreparedStatement ps = conn.prepareStatement("DELETE FROM safe_movement WHERE id = ?"))
		{
			ps.setLong(1, id);
			ps.executeUpdate();
		}
	}

	/** Physical cash in the safe right now (all time — a safe does not reset). */
	public double balance()
	{
		final String sql = "SELECT COALESCE(SUM(CASE WHEN type = 'IN' THEN amount ELSE -amount END), 0) FROM safe_movement";
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

	/** Total sent from the safe to the bank, all time. */
	public double totalToBank()
	{
		final String sql = "SELECT COALESCE(SUM(amount),0) FROM safe_movement WHERE type = 'BANK'";
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

	/** Most recent safe movements, newest first. */
	public List<CashMovement> listRecent(int limit) throws SQLException
	{
		final String sql = """
				SELECT * FROM safe_movement
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
