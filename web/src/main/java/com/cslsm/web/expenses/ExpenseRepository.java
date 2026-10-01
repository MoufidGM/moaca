package com.cslsm.web.expenses;

import com.cslsm.web.expenses.ExpenseModels.ExpenseDraft;
import com.cslsm.web.expenses.ExpenseModels.ExpenseRow;
import com.cslsm.web.expenses.ExpenseModels.ExpenseSearch;
import com.cslsm.web.expenses.ExpenseModels.Totals;
import com.cslsm.web.finance.ExpenseSql;
import com.cslsm.web.support.Keys;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@DependsOn("flyway")
public class ExpenseRepository
{
	private static final int MAX_ROWS = 1000;

	/** ExpenseSql.PAID_FROM with the table alias. */
	private static final String PAID_FROM_E = ExpenseSql.paidFrom("e.");

	private static final String SELECT = "SELECT e.*, " + PAID_FROM_E + " AS paid_from_effective, "
			+ "(SELECT COUNT(*) FROM expense_attachment a WHERE a.expense_id = e.id) AS attachment_count, "
			+ "(SELECT COUNT(*) FROM expense_allocation x WHERE x.expense_id = e.id) AS split_count, "
			+ "(SELECT m.name FROM employee m WHERE m.id = e.employee_id) AS employee_name "
			+ "FROM expense e ";

	private static final RowMapper<ExpenseRow> MAPPER = (rs, n) -> {
		long createdBy = rs.getLong("created_by_user_id");
		boolean noCreator = rs.wasNull();
		long employee = rs.getLong("employee_id");
		boolean noEmployee = rs.wasNull();
		return new ExpenseRow(rs.getLong("id"), LocalDate.parse(rs.getString("expense_date")),
				rs.getString("category"), rs.getString("description"), rs.getDouble("amount"),
				rs.getString("payment_method"), rs.getString("activity"), rs.getString("paid_from_effective"),
				rs.getString("status"), rs.getString("entered_by"), rs.getString("approved_by"),
				rs.getString("approved_at"), rs.getString("rejection_reason"),
				noCreator ? null : createdBy, rs.getString("created_at"), rs.getInt("attachment_count"),
				rs.getInt("split_count"), noEmployee ? null : employee, rs.getString("employee_name"));
	};

	private final JdbcTemplate jdbc;

	public ExpenseRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	public Optional<ExpenseRow> find(long id)
	{
		return jdbc.query(SELECT + "WHERE e.id = ?", MAPPER, id).stream().findFirst();
	}

	public List<ExpenseRow> search(ExpenseSearch s)
	{
		StringBuilder sql = new StringBuilder(SELECT).append("WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (s.from() != null)
		{
			sql.append(" AND e.expense_date >= ?");
			args.add(s.from().toString());
		}
		if (s.to() != null)
		{
			sql.append(" AND e.expense_date <= ?");
			args.add(s.to().toString());
		}
		if (s.status() != null)
		{
			sql.append(" AND e.status = ?");
			args.add(s.status());
		}
		if (s.category() != null)
		{
			sql.append(" AND e.category = ?");
			args.add(s.category());
		}
		if (s.activity() != null)
		{
			sql.append(" AND e.activity = ?");
			args.add(s.activity());
		}
		if (s.paidFrom() != null)
		{
			sql.append(" AND ").append(PAID_FROM_E).append(" = ?");
			args.add(s.paidFrom());
		}
		if (s.text() != null)
		{
			String like = "%" + s.text().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
			sql.append(" AND (e.description LIKE ? ESCAPE '\\' OR e.category LIKE ? ESCAPE '\\'"
					+ " OR e.entered_by LIKE ? ESCAPE '\\' OR e.activity LIKE ? ESCAPE '\\')");
			args.add(like);
			args.add(like);
			args.add(like);
			args.add(like);
		}
		if (s.createdBy() != null)
		{
			sql.append(" AND e.created_by_user_id = ?");
			args.add(s.createdBy());
		}
		if (s.bankSuspects())
		{
			sql.append(" AND e.").append(ExpenseSql.COUNTS).append(" AND ").append(ExpenseSql.LOOKS_LIKE_BANK_DEPOSIT.replace("description", "e.description"));
		}
		sql.append(" ORDER BY e.expense_date DESC, e.id DESC LIMIT ").append(MAX_ROWS);
		return jdbc.query(sql.toString(), MAPPER, args.toArray());
	}

	public long insert(ExpenseDraft d)
	{
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement("""
					INSERT INTO expense (expense_date, category, description, amount, payment_method, activity,
					                     paid_from, till, paid_from_storage, status, entered_by, approved_by, approved_at,
					                     created_by_user_id, employee_id)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""", Statement.RETURN_GENERATED_KEYS);
			ps.setString(1, d.date().toString());
			ps.setString(2, d.category());
			ps.setString(3, d.description());
			ps.setDouble(4, d.amount());
			ps.setString(5, d.paymentMethod());
			ps.setString(6, d.activity());
			ps.setString(7, ExpenseModels.storedPaidFrom(d.paidFrom()));
			ps.setString(8, ExpenseModels.storedTill(d.paidFrom()));
			// The desktop app's balance only knows the reception till
			ps.setInt(9, "RECEPTION".equals(d.paidFrom()) ? 1 : 0);
			ps.setString(10, d.status());
			ps.setString(11, d.enteredBy());
			ps.setString(12, d.approvedBy());
			ps.setString(13, d.approvedAt());
			ps.setObject(14, d.createdByUserId());
			ps.setObject(15, d.employeeId());
			return ps;
		}, keys);
		return Keys.generatedId(keys);
	}

	public void update(long id, ExpenseDraft d)
	{
		jdbc.update("""
						UPDATE expense
						SET expense_date = ?, category = ?, description = ?, amount = ?, payment_method = ?,
						    activity = ?, paid_from = ?, till = ?, paid_from_storage = ?, employee_id = ?
						WHERE id = ?
						""",
				d.date().toString(), d.category(), d.description(), d.amount(), d.paymentMethod(),
				d.activity(), ExpenseModels.storedPaidFrom(d.paidFrom()), ExpenseModels.storedTill(d.paidFrom()),
				"RECEPTION".equals(d.paidFrom()) ? 1 : 0, d.employeeId(), id);
	}

	public void delete(long id)
	{
		jdbc.update("DELETE FROM expense WHERE id = ?", id);
	}

	public boolean approve(long id, String approver)
	{
		return jdbc.update("""
				UPDATE expense SET status = 'APPROVED', approved_by = ?, approved_at = ?, rejection_reason = NULL
				WHERE id = ? AND status = 'PENDING'
				""", approver, now(), id) == 1;
	}

	public boolean reject(long id, String approver, String reason, boolean alsoApproved)
	{
		return jdbc.update("UPDATE expense SET status = 'REJECTED', approved_by = ?, approved_at = ?, rejection_reason = ? "
				+ "WHERE id = ? AND status " + (alsoApproved ? "IN ('PENDING', 'APPROVED')" : "= 'PENDING'"),
				approver, now(), reason, id) == 1;
	}

	public Totals pending()
	{
		return jdbc.queryForObject("SELECT COUNT(*), COALESCE(SUM(amount), 0) FROM expense WHERE status = 'PENDING'",
				(rs, n) -> new Totals(rs.getInt(1), rs.getDouble(2)));
	}

	public Totals suspectedBankDeposits()
	{
		return jdbc.queryForObject("SELECT COUNT(*), COALESCE(SUM(amount), 0) FROM expense WHERE "
						+ ExpenseSql.COUNTS + " AND " + ExpenseSql.LOOKS_LIKE_BANK_DEPOSIT,
				(rs, n) -> new Totals(rs.getInt(1), rs.getDouble(2)));
	}

	/** Salary expenses already recorded for an employee in a month (payroll duplicate check). */
	public double salaryPaid(long employeeId, java.time.YearMonth month)
	{
		Double v = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM expense WHERE employee_id = ? AND "
						+ ExpenseSql.COUNTS + " AND substr(expense_date, 1, 7) = ? AND category = 'Salaries'",
				Double.class, employeeId, month.toString());
		return v == null ? 0 : v;
	}

	/** An existing expense on the same day with the same category and amount (possible double entry). */
	public Optional<Long> similar(LocalDate date, String category, double amount, Long excludeId)
	{
		return jdbc.queryForList("""
						SELECT id FROM expense
						WHERE expense_date = ? AND category = ? AND ABS(amount - ?) < 0.005 AND status <> 'REJECTED' AND id <> ?
						ORDER BY id LIMIT 1
						""", Long.class, date.toString(), category, amount, excludeId == null ? -1 : excludeId)
				.stream().findFirst();
	}

	public List<String> categoriesInUse()
	{
		return jdbc.queryForList("SELECT DISTINCT category FROM expense WHERE category IS NOT NULL ORDER BY category", String.class);
	}

	public List<String> activitiesInUse()
	{
		return jdbc.queryForList("SELECT DISTINCT activity FROM expense WHERE activity IS NOT NULL AND activity <> '' ORDER BY activity", String.class);
	}

	private static String now()
	{
		return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
	}
}
