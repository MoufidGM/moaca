package com.cslsm.web.expenses;

import com.cslsm.web.expenses.ExpenseModels.Option;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@DependsOn("flyway")
public class ExpenseOptionRepository
{
	private static final RowMapper<Option> MAPPER = (rs, n) -> new Option(
			rs.getLong("id"), rs.getString("kind"), rs.getString("name"), rs.getInt("admin_only") != 0,
			rs.getInt("active") != 0, rs.getString("cost_rule"), rs.getString("income_column"), rs.getString("part_of"),
			rs.getString("heading"));

	private final JdbcTemplate jdbc;

	public ExpenseOptionRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	/** Categories a user may pick: receptionists never see admin-only ones. */
	public List<Option> categories(boolean includeAdminOnly)
	{
		return jdbc.query("""
				SELECT * FROM expense_option
				WHERE kind = 'CATEGORY' AND active = 1 AND (admin_only = 0 OR ?)
				ORDER BY sort_order, name
				""", MAPPER, includeAdminOnly ? 1 : 0);
	}

	/** Activities offered in forms. */
	public List<Option> activities()
	{
		return jdbc.query("SELECT * FROM expense_option WHERE kind = 'ACTIVITY' AND active = 1 ORDER BY sort_order, name", MAPPER);
	}

	/** Every activity, including switched-off ones (old expenses may still use them). */
	public List<Option> allActivities()
	{
		return jdbc.query("SELECT * FROM expense_option WHERE kind = 'ACTIVITY' ORDER BY active DESC, sort_order, name", MAPPER);
	}

	/** Case-insensitive lookup returning the canonical spelling. */
	public Optional<Option> findCategory(String name)
	{
		return find("CATEGORY", name, true);
	}

	public Optional<Option> findActivity(String name)
	{
		return find("ACTIVITY", name, true);
	}

	public Optional<Option> findActivityIncludingInactive(String name)
	{
		return find("ACTIVITY", name, false);
	}

	public Optional<Option> findById(long id)
	{
		return jdbc.query("SELECT * FROM expense_option WHERE id = ?", MAPPER, id).stream().findFirst();
	}

	public void updateActivityRule(long id, String costRule, String partOf, boolean active)
	{
		jdbc.update("UPDATE expense_option SET cost_rule = ?, part_of = ?, active = ? WHERE id = ? AND kind = 'ACTIVITY'",
				costRule, partOf, active ? 1 : 0, id);
	}

	/** Category name -> heading, for the analysis. */
	public java.util.Map<String, String> categoryHeadings()
	{
		java.util.Map<String, String> out = new java.util.HashMap<>();
		jdbc.query("SELECT name, heading FROM expense_option WHERE kind = 'CATEGORY'",
				rs -> { out.put(rs.getString(1).toLowerCase(java.util.Locale.ROOT), rs.getString(2)); });
		return out;
	}

	public void createActivity(String name, String costRule, String partOf)
	{
		jdbc.update("INSERT INTO expense_option (kind, name, cost_rule, part_of, sort_order) VALUES ('ACTIVITY', ?, ?, ?, 500)",
				name, costRule, partOf);
	}

	private Optional<Option> find(String kind, String name, boolean activeOnly)
	{
		if (name == null || name.isBlank())
		{
			return Optional.empty();
		}
		return jdbc.query("SELECT * FROM expense_option WHERE kind = ? AND name = ?" + (activeOnly ? " AND active = 1" : ""),
				MAPPER, kind, name.trim()).stream().findFirst();
	}
}
