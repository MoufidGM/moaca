package com.cslsm.web.admin;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Expense categories as the Administration page sees them (expense_option, kind CATEGORY). */
@Repository
@DependsOn("flyway")
public class CategoryRepository
{
	public record Category(long id, String name, String heading, boolean adminOnly, boolean active, int sortOrder, int used)
	{
	}

	private static final String SELECT = "SELECT o.*, (SELECT COUNT(*) FROM expense e WHERE e.category = o.name) AS used "
			+ "FROM expense_option o WHERE o.kind = 'CATEGORY'";

	private static final RowMapper<Category> MAPPER = (rs, n) -> new Category(rs.getLong("id"), rs.getString("name"),
			rs.getString("heading"), rs.getInt("admin_only") != 0, rs.getInt("active") != 0, rs.getInt("sort_order"), rs.getInt("used"));

	private final JdbcTemplate jdbc;

	public CategoryRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	public List<Category> all()
	{
		return jdbc.query(SELECT + " ORDER BY o.active DESC, o.sort_order, o.name", MAPPER);
	}

	public Optional<Category> find(long id)
	{
		return jdbc.query(SELECT + " AND o.id = ?", MAPPER, id).stream().findFirst();
	}

	/** Case-insensitive (the column is NOCASE). */
	public Optional<Category> findByName(String name)
	{
		return jdbc.query(SELECT + " AND o.name = ?", MAPPER, name).stream().findFirst();
	}

	public void insert(String name, String heading, boolean adminOnly, int sortOrder)
	{
		jdbc.update("INSERT INTO expense_option (kind, name, heading, admin_only, sort_order) VALUES ('CATEGORY', ?, ?, ?, ?)",
				name, heading, adminOnly ? 1 : 0, sortOrder);
	}

	public void update(long id, String heading, boolean adminOnly, boolean active, int sortOrder)
	{
		jdbc.update("UPDATE expense_option SET heading = ?, admin_only = ?, active = ?, sort_order = ? WHERE id = ? AND kind = 'CATEGORY'",
				heading, adminOnly ? 1 : 0, active ? 1 : 0, sortOrder, id);
	}
}
