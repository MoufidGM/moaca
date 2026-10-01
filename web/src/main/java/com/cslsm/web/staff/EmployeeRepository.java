package com.cslsm.web.staff;

import com.cslsm.web.expenses.ExpenseModels.Split;
import com.cslsm.web.support.Keys;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
@DependsOn("flyway")
public class EmployeeRepository
{
	/** An employee and how their pay divides between activities. */
	public record Employee(long id, String name, String job, Double monthlySalary, boolean active, List<Split> splits)
	{
		/** "Académie" or "Location de terrains 70% · Gym 30%". */
		public String splitLabel()
		{
			if (splits.isEmpty())
			{
				return "—";
			}
			if (splits.size() == 1)
			{
				return splits.get(0).activity();
			}
			StringBuilder sb = new StringBuilder();
			for (Split s : splits)
			{
				if (sb.length() > 0)
				{
					sb.append(" · ");
				}
				sb.append(s.activity()).append(' ').append(String.format(java.util.Locale.ROOT, "%.0f", s.percent())).append('%');
			}
			return sb.toString();
		}

		public boolean isDirect()
		{
			return splits.size() == 1;
		}
	}

	private static final RowMapper<Employee> MAPPER = (rs, n) -> {
		double salary = rs.getDouble("monthly_salary");
		boolean noSalary = rs.wasNull();
		return new Employee(rs.getLong("id"), rs.getString("name"), rs.getString("job"), noSalary ? null : salary,
				rs.getInt("active") != 0, List.of());
	};

	private final JdbcTemplate jdbc;

	public EmployeeRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	public List<Employee> all(boolean includeInactive)
	{
		List<Employee> rows = jdbc.query("SELECT * FROM employee" + (includeInactive ? "" : " WHERE active = 1")
				+ " ORDER BY active DESC, name", MAPPER);
		return rows.stream().map(this::withSplits).toList();
	}

	public Optional<Employee> find(long id)
	{
		return jdbc.query("SELECT * FROM employee WHERE id = ?", MAPPER, id).stream().findFirst().map(this::withSplits);
	}

	public Optional<Employee> findByName(String name)
	{
		return jdbc.query("SELECT * FROM employee WHERE name = ?", MAPPER, name.trim()).stream().findFirst().map(this::withSplits);
	}

	private Employee withSplits(Employee e)
	{
		List<Split> splits = jdbc.query("SELECT activity, percent FROM employee_split WHERE employee_id = ? ORDER BY percent DESC, activity",
				(rs, n) -> new Split(rs.getString(1), rs.getDouble(2)), e.id());
		return new Employee(e.id(), e.name(), e.job(), e.monthlySalary(), e.active(), splits);
	}

	public long insert(String name, String job, Double salary, List<Split> splits)
	{
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement("INSERT INTO employee (name, job, monthly_salary) VALUES (?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setString(1, name);
			ps.setString(2, job);
			ps.setObject(3, salary);
			return ps;
		}, keys);
		long id = Keys.generatedId(keys);
		replaceSplits(id, splits);
		return id;
	}

	public void update(long id, String name, String job, Double salary, boolean active, List<Split> splits)
	{
		jdbc.update("UPDATE employee SET name = ?, job = ?, monthly_salary = ?, active = ? WHERE id = ?",
				name, job, salary, active ? 1 : 0, id);
		replaceSplits(id, splits);
	}

	private void replaceSplits(long id, List<Split> splits)
	{
		jdbc.update("DELETE FROM employee_split WHERE employee_id = ?", id);
		for (Split s : splits)
		{
			jdbc.update("INSERT INTO employee_split (employee_id, activity, percent) VALUES (?, ?, ?)", id, s.activity(), s.percent());
		}
	}

	/** Renames an activity on employee splits (activity merges). */
	public void renameActivity(String from, String to)
	{
		jdbc.update("UPDATE OR IGNORE employee_split SET activity = ? WHERE activity = ?", to, from);
	}
}
