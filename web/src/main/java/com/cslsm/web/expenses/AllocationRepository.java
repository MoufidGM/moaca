package com.cslsm.web.expenses;

import com.cslsm.web.expenses.ExpenseModels.Split;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Splits of one expense across several activities (expense_allocation, V15). */
@Repository
@DependsOn("flyway")
public class AllocationRepository
{
	private final JdbcTemplate jdbc;

	public AllocationRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	public List<Split> forExpense(long expenseId)
	{
		return jdbc.query("SELECT activity, percent FROM expense_allocation WHERE expense_id = ? ORDER BY percent DESC, activity",
				(rs, n) -> new Split(rs.getString(1), rs.getDouble(2)), expenseId);
	}

	/** Replaces the split of an expense; an empty list means "100% to its activity". */
	public void replace(long expenseId, List<Split> splits)
	{
		jdbc.update("DELETE FROM expense_allocation WHERE expense_id = ?", expenseId);
		for (Split s : splits)
		{
			jdbc.update("INSERT INTO expense_allocation (expense_id, activity, percent) VALUES (?, ?, ?)",
					expenseId, s.activity(), s.percent());
		}
	}

	public void deleteForExpense(long expenseId)
	{
		jdbc.update("DELETE FROM expense_allocation WHERE expense_id = ?", expenseId);
	}
}
