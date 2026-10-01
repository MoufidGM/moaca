package com.cslsm.web.restaurant;

import com.cslsm.web.finance.FinanceModels.NamedAmount;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * What the family eats at Tiki Taka (V23): one line per meal from the daily file, tracked per
 * person. Nobody pays it, so it is neither cash nor revenue — just known.
 */
@Repository
@DependsOn("flyway")
public class FamilyRepository
{
	public record Meal(long id, LocalDate date, String member, double amount, String note)
	{
	}

	private final JdbcTemplate jdbc;

	public FamilyRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	public void insert(LocalDate date, String member, double amount, String note, long importId)
	{
		jdbc.update("INSERT INTO family_consumption (sale_date, member, amount, note, import_id) VALUES (?, ?, ?, ?, ?)",
				date.toString(), member, amount, note, importId);
	}

	/** A replaced file replaces its lines. */
	public int deleteFromImports(List<Long> importIds)
	{
		int n = 0;
		for (Long id : importIds)
		{
			n += jdbc.update("DELETE FROM family_consumption WHERE import_id = ?", id);
		}
		return n;
	}

	/** Per person over a period, biggest first. */
	public List<NamedAmount> byMember(LocalDate from, LocalDate to)
	{
		return jdbc.query("SELECT member, SUM(amount) AS total FROM family_consumption WHERE sale_date BETWEEN ? AND ?"
						+ " GROUP BY member ORDER BY total DESC, member",
				(rs, n) -> new NamedAmount(rs.getString("member"), rs.getDouble("total")), from.toString(), to.toString());
	}

	public List<Meal> between(LocalDate from, LocalDate to)
	{
		return jdbc.query("SELECT * FROM family_consumption WHERE sale_date BETWEEN ? AND ? ORDER BY sale_date DESC, id DESC",
				(rs, n) -> new Meal(rs.getLong("id"), LocalDate.parse(rs.getString("sale_date")), rs.getString("member"),
						rs.getDouble("amount"), rs.getString("note")), from.toString(), to.toString());
	}

	public double total(LocalDate from, LocalDate to)
	{
		Double v = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM family_consumption WHERE sale_date BETWEEN ? AND ?",
				Double.class, from.toString(), to.toString());
		return v == null ? 0 : v;
	}
}
