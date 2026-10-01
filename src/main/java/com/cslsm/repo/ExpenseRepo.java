package com.cslsm.repo;

import com.cslsm.model.Expense;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExpenseRepo
{

	private final String jdbcUrl;

	public ExpenseRepo(String dbPath)
	{
		this.jdbcUrl = "jdbc:sqlite:" + dbPath + "?busy_timeout=5000";
	}

	private static Expense mapRow(ResultSet rs) throws SQLException
	{
		Expense e = new Expense();
		e.setId(rs.getLong("id"));
		e.setExpenseDate(rs.getString("expense_date"));
		e.setCategory(rs.getString("category"));
		e.setDescription(rs.getString("description"));
		e.setAmount((Double) rs.getObject("amount"));
		e.setPaymentMethod(rs.getString("payment_method"));
		e.setEnteredBy(rs.getString("entered_by"));
		e.setApprovedBy(rs.getString("approved_by"));
		e.setActivity(rs.getString("activity"));
		e.setPaidFromStorage(rs.getInt("paid_from_storage") != 0);
		return e;
	}

	public long insert(Expense e) throws SQLException
	{
		final String sql = """
				INSERT INTO expense (expense_date, category, description, amount, payment_method, entered_by, approved_by, activity, paid_from_storage)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""";
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS))
		{
			int i = 1;
			ps.setString(i++, e.getExpenseDate());
			ps.setString(i++, e.getCategory());
			ps.setString(i++, e.getDescription());
			ps.setObject(i++, e.getAmount());
			ps.setString(i++, e.getPaymentMethod());
			ps.setString(i++, e.getEnteredBy());
			ps.setString(i++, e.getApprovedBy());
			ps.setString(i++, e.getActivity());
			ps.setInt(i++, e.isPaidFromStorage() ? 1 : 0);
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys())
			{
				if (keys.next()) return keys.getLong(1);
			}
			return -1;
		}
	}

	public void bulkInsert(List<Expense> items) throws SQLException
	{
		final String sql = """
				INSERT INTO expense (expense_date, category, description, amount, payment_method, entered_by, approved_by, activity, paid_from_storage)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""";
		try (Connection conn = DriverManager.getConnection(jdbcUrl))
		{
			conn.setAutoCommit(false);
			try (PreparedStatement ps = conn.prepareStatement(sql))
			{
				for (Expense e : items)
				{
					int i = 1;
					ps.setString(i++, e.getExpenseDate());
					ps.setString(i++, e.getCategory());
					ps.setString(i++, e.getDescription());
					ps.setObject(i++, e.getAmount());
					ps.setString(i++, e.getPaymentMethod());
					ps.setString(i++, e.getEnteredBy());
					ps.setString(i++, e.getApprovedBy());
					ps.setString(i++, e.getActivity());
					ps.setInt(i++, e.isPaidFromStorage() ? 1 : 0);
					ps.addBatch();
				}
				ps.executeBatch();
			}
			conn.commit();
		}
	}

	public void update(Expense e) throws SQLException
	{
		final String sql = """
				UPDATE expense
				SET expense_date = ?, category = ?, description = ?, amount = ?, payment_method = ?,
				    entered_by = ?, approved_by = ?, activity = ?, paid_from_storage = ?
				WHERE id = ?
				""";
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement(sql))
		{
			int i = 1;
			ps.setString(i++, e.getExpenseDate());
			ps.setString(i++, e.getCategory());
			ps.setString(i++, e.getDescription());
			ps.setObject(i++, e.getAmount());
			ps.setString(i++, e.getPaymentMethod());
			ps.setString(i++, e.getEnteredBy());
			ps.setString(i++, e.getApprovedBy());
			ps.setString(i++, e.getActivity());
			ps.setInt(i++, e.isPaidFromStorage() ? 1 : 0);
			ps.setLong(i++, e.getId());
			ps.executeUpdate();
		}
	}

	public void delete(long id) throws SQLException
	{
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement("DELETE FROM expense WHERE id = ?"))
		{
			ps.setLong(1, id);
			ps.executeUpdate();
		}
	}

	/** All expenses between two dates inclusive, ordered by date then id. */
	public List<Expense> findBetween(LocalDate from, LocalDate to) throws SQLException
	{
		final String sql = """
				SELECT * FROM expense
				WHERE expense_date BETWEEN ? AND ?
				ORDER BY expense_date, id
				""";
		List<Expense> out = new ArrayList<>();
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement(sql))
		{
			ps.setString(1, from.toString());
			ps.setString(2, to.toString());
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next()) out.add(mapRow(rs));
			}
		}
		return out;
	}

	/** Total spend between two dates inclusive (0 when none). */
	public double sumBetween(LocalDate from, LocalDate to)
	{
		final String sql = "SELECT COALESCE(SUM(amount),0) FROM expense WHERE expense_date BETWEEN ? AND ?";
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement(sql))
		{
			ps.setString(1, from.toString());
			ps.setString(2, to.toString());
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

	/** category -> total between two dates inclusive, largest first. */
	public Map<String, Double> sumByCategoryBetween(LocalDate from, LocalDate to)
	{
		final String sql = """
				SELECT category, COALESCE(SUM(amount),0) AS total
				FROM expense
				WHERE expense_date BETWEEN ? AND ?
				GROUP BY category
				ORDER BY total DESC
				""";
		Map<String, Double> out = new LinkedHashMap<>();
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement(sql))
		{
			ps.setString(1, from.toString());
			ps.setString(2, to.toString());
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next()) out.put(rs.getString(1), rs.getDouble(2));
			}
		}
		catch (SQLException e)
		{
			e.printStackTrace();
		}
		return out;
	}

	/** activity -> total between two dates inclusive, largest first. */
	public Map<String, Double> sumByActivityBetween(LocalDate from, LocalDate to)
	{
		final String sql = """
				SELECT COALESCE(NULLIF(TRIM(activity), ''), 'General') AS act, COALESCE(SUM(amount),0) AS total
				FROM expense
				WHERE expense_date BETWEEN ? AND ?
				GROUP BY act
				ORDER BY total DESC
				""";
		Map<String, Double> out = new LinkedHashMap<>();
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement(sql))
		{
			ps.setString(1, from.toString());
			ps.setString(2, to.toString());
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next()) out.put(rs.getString(1), rs.getDouble(2));
			}
		}
		catch (SQLException e)
		{
			e.printStackTrace();
		}
		return out;
	}

	/** All distinct categories ever used (for combo suggestions). */
	public List<String> distinctCategories()
	{
		List<String> out = new ArrayList<>();
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement("SELECT DISTINCT category FROM expense ORDER BY category");
			 ResultSet rs = ps.executeQuery())
		{
			while (rs.next()) out.add(rs.getString(1));
		}
		catch (SQLException e)
		{
			e.printStackTrace();
		}
		return out;
	}
}
