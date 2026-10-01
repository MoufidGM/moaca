package com.cslsm.web.expenses;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Frequent expenses (V20): one tap on the expense form fills the category, activity, usual
 * amount and where the money comes from. Admins make them from an existing expense.
 */
@Repository
@DependsOn("flyway")
public class ExpensePresetRepository
{
	public record Preset(long id, String name, String category, String activity, Double amount, String paidFrom, int sortOrder, boolean active)
	{
	}

	private static final RowMapper<Preset> MAPPER = (rs, n) -> {
		double amount = rs.getDouble("amount");
		boolean noAmount = rs.wasNull();
		return new Preset(rs.getLong("id"), rs.getString("name"), rs.getString("category"), rs.getString("activity"),
				noAmount ? null : amount, rs.getString("paid_from"), rs.getInt("sort_order"), rs.getInt("active") != 0);
	};

	private final JdbcTemplate jdbc;

	public ExpensePresetRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	/** Presets a user may tap: only those whose category they may use. */
	public List<Preset> forUser(boolean admin)
	{
		return jdbc.query("SELECT p.* FROM expense_preset p JOIN expense_option o ON o.kind = 'CATEGORY' AND o.name = p.category"
				+ " WHERE p.active = 1 AND o.active = 1 AND (o.admin_only = 0 OR ?) ORDER BY p.sort_order, p.name", MAPPER, admin ? 1 : 0);
	}

	public List<Preset> all()
	{
		return jdbc.query("SELECT * FROM expense_preset ORDER BY active DESC, sort_order, name", MAPPER);
	}

	public Optional<Preset> find(long id)
	{
		return jdbc.query("SELECT * FROM expense_preset WHERE id = ?", MAPPER, id).stream().findFirst();
	}

	public Optional<Preset> findByName(String name)
	{
		return jdbc.query("SELECT * FROM expense_preset WHERE name = ?", MAPPER, name).stream().findFirst();
	}

	public void insert(String name, String category, String activity, Double amount, String paidFrom)
	{
		jdbc.update("INSERT INTO expense_preset (name, category, activity, amount, paid_from) VALUES (?, ?, ?, ?, ?)",
				name, category, activity, amount, paidFrom);
	}

	public void setActive(long id, boolean active)
	{
		jdbc.update("UPDATE expense_preset SET active = ? WHERE id = ?", active ? 1 : 0, id);
	}
}
