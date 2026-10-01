package com.cslsm.web.activities;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

/** Percentages per activity for the two shared-cost pools (cost_key, V16). */
@Repository
@DependsOn("flyway")
public class CostKeyRepository
{
	public static final String UTILITIES = "UTILITIES";
	public static final String COMMON = "COMMON";

	private final JdbcTemplate jdbc;

	public CostKeyRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	/** activity -> percent; empty when no key is defined for the kind. */
	public Map<String, Double> key(String kind)
	{
		Map<String, Double> out = new LinkedHashMap<>();
		jdbc.query("SELECT activity, percent FROM cost_key WHERE kind = ? ORDER BY percent DESC, activity",
				rs -> { out.put(rs.getString(1), rs.getDouble(2)); }, kind);
		return out;
	}

	public void replace(String kind, Map<String, Double> key)
	{
		jdbc.update("DELETE FROM cost_key WHERE kind = ?", kind);
		key.forEach((activity, percent) ->
				jdbc.update("INSERT INTO cost_key (kind, activity, percent) VALUES (?, ?, ?)", kind, activity, percent));
	}

	public void renameActivity(String from, String to)
	{
		jdbc.update("UPDATE OR IGNORE cost_key SET activity = ? WHERE activity = ?", to, from);
	}
}
