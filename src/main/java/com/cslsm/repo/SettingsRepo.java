package com.cslsm.repo;

import java.sql.*;

public class SettingsRepo
{

	private final String jdbcUrl;

	public SettingsRepo(String dbPath)
	{
		this.jdbcUrl = "jdbc:sqlite:" + dbPath + "?busy_timeout=5000";
	}

	public String get(String key, String defaultValue)
	{
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement("SELECT value FROM app_setting WHERE key = ?"))
		{
			ps.setString(1, key);
			try (ResultSet rs = ps.executeQuery())
			{
				if (rs.next())
				{
					String v = rs.getString(1);
					return v == null ? defaultValue : v;
				}
			}
		}
		catch (SQLException e)
		{
			e.printStackTrace();
		}
		return defaultValue;
	}

	public void set(String key, String value)
	{
		final String sql = """
				INSERT INTO app_setting (key, value) VALUES (?, ?)
				ON CONFLICT(key) DO UPDATE SET value = excluded.value
				""";
		try (Connection conn = DriverManager.getConnection(jdbcUrl);
			 PreparedStatement ps = conn.prepareStatement(sql))
		{
			ps.setString(1, key);
			ps.setString(2, value);
			ps.executeUpdate();
		}
		catch (SQLException e)
		{
			e.printStackTrace();
		}
	}
}
