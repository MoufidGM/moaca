package com.cslsm.repo;

import com.cslsm.model.LineItem;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

public class LineItemRepo
{

	private final String jdbcUrl;

	public LineItemRepo(String dbPath)
	{
		this.jdbcUrl = "jdbc:sqlite:" + dbPath + "?busy_timeout=5000";
	}

	public void deleteByDate(String isoDate) throws SQLException
	{
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement("DELETE FROM line_item WHERE log_date = ?"))
		{
			ps.setString(1, isoDate);
			ps.executeUpdate();
		}
	}

	public void insert(LineItem li) throws SQLException
	{
		final String sql = """
				INSERT INTO line_item
				    (log_date, point_de_vente, prestation, quantite, prix, total)
				VALUES (?, ?, ?, ?, ?, ?)
				""";
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement(sql))
		{
			int i = 1;
			ps.setString(i++, li.getLogDate()); // REQUIRED: provided by caller
			ps.setString(i++, li.getPointDeVente());
			ps.setString(i++, li.getPrestation());
			ps.setObject(i++, li.getQuantite());
			ps.setObject(i++, li.getPrix());
			ps.setObject(i++, li.getTotal());
			ps.executeUpdate();
		}
	}

	public void bulkInsert(List<LineItem> items) throws SQLException
	{
		final String sql = """
				INSERT INTO line_item
				    (log_date, point_de_vente, prestation, quantite, prix, total)
				VALUES (?, ?, ?, ?, ?, ?)
				""";
		try (Connection conn = DriverManager.getConnection(jdbcUrl))
		{
			conn.setAutoCommit(false);
			try (PreparedStatement ps = conn.prepareStatement(sql))
			{
				for (LineItem li : items)
				{
					int i = 1;
					ps.setString(i++, li.getLogDate());
					ps.setString(i++, li.getPointDeVente());
					ps.setString(i++, li.getPrestation());
					ps.setObject(i++, li.getQuantite());
					ps.setObject(i++, li.getPrix());
					ps.setObject(i++, li.getTotal());
					ps.addBatch();
				}
				ps.executeBatch();
			}
			conn.commit();
		}
	}
}
