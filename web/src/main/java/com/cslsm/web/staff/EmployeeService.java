package com.cslsm.web.staff;

import com.cslsm.web.expenses.AllocationRepository;
import com.cslsm.web.expenses.ExpenseModels;
import com.cslsm.web.expenses.ExpenseModels.ExpenseDraft;
import com.cslsm.web.expenses.ExpenseModels.Option;
import com.cslsm.web.expenses.ExpenseModels.Split;
import com.cslsm.web.expenses.ExpenseOptionRepository;
import com.cslsm.web.expenses.ExpenseRepository;
import com.cslsm.web.staff.EmployeeRepository.Employee;
import com.cslsm.web.support.Actor;
import com.cslsm.web.support.AuditService;
import com.cslsm.web.support.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Staff: each employee's pay divides between activities once (100% one activity for coaches,
 * a split for shared staff). Payroll then records one salary expense per employee per month,
 * already split, so the activity analysis gets direct and shared salaries automatically.
 */
@Service
public class EmployeeService
{
	public static class StaffException extends RuntimeException
	{
		public StaffException(String message)
		{
			super(message);
		}
	}

	/** Input for one employee: name, job, monthly salary, split rows (activity, percent). */
	public record EmployeeInput(String name, String job, String salary, boolean active,
								List<String> splitActivity, List<String> splitPercent)
	{
	}

	/** One line of a payroll run. */
	public record PayLine(Employee employee, double alreadyPaid, String amount)
	{
	}

	private final EmployeeRepository employees;
	private final ExpenseOptionRepository options;
	private final ExpenseRepository expenses;
	private final AllocationRepository allocations;
	private final AuditService audit;
	private final TransactionTemplate tx;
	private final Clock clock;

	public EmployeeService(EmployeeRepository employees, ExpenseOptionRepository options, ExpenseRepository expenses,
						   AllocationRepository allocations, AuditService audit, TransactionTemplate tx, Clock clock)
	{
		this.employees = employees;
		this.options = options;
		this.expenses = expenses;
		this.allocations = allocations;
		this.audit = audit;
		this.tx = tx;
		this.clock = clock;
	}

	/* ======================= employees ======================= */

	public long create(EmployeeInput in, Actor actor)
	{
		requireAdmin(actor);
		String name = checkedName(in.name(), null);
		List<Split> splits = checkedSplits(in.splitActivity(), in.splitPercent());
		Double salary = checkedSalary(in.salary());
		return tx.execute(s -> {
			long id = employees.insert(name, job(in.job()), salary, splits);
			audit.record(actor, "EMPLOYEE_CREATE", "employee", id, name + " " + describe(splits));
			return id;
		});
	}

	public void update(long id, EmployeeInput in, Actor actor)
	{
		requireAdmin(actor);
		Employee before = employees.find(id).orElseThrow(() -> new StaffException("Employee not found."));
		String name = checkedName(in.name(), id);
		List<Split> splits = checkedSplits(in.splitActivity(), in.splitPercent());
		Double salary = checkedSalary(in.salary());
		tx.executeWithoutResult(s -> {
			employees.update(id, name, job(in.job()), salary, in.active(), splits);
			audit.record(actor, "EMPLOYEE_UPDATE", "employee", id,
					before.name() + " " + describe(before.splits()) + " -> " + name + " " + describe(splits) + (in.active() ? "" : " (off)"));
		});
	}

	/** The split to apply to a salary expense of this employee (empty when 100% one activity). */
	public List<Split> allocationFor(Employee e)
	{
		return e.splits().size() <= 1 ? List.of() : e.splits();
	}

	/** The activity to write on a salary expense: the largest share. */
	public String mainActivity(Employee e)
	{
		return e.splits().isEmpty() ? "General" : e.splits().get(0).activity();
	}

	/* ======================= payroll ======================= */

	public List<PayLine> payrollLines(YearMonth month)
	{
		List<PayLine> out = new ArrayList<>();
		for (Employee e : employees.all(false))
		{
			double paid = expenses.salaryPaid(e.id(), month);
			String suggested = e.monthlySalary() == null ? "" : String.format(Locale.ROOT, "%.2f", e.monthlySalary()).replaceAll("\\.?0+$", "");
			out.add(new PayLine(e, paid, paid > 0 ? "" : suggested));
		}
		return out;
	}

	/**
	 * Records the salaries typed for a month: one approved expense per employee with a
	 * non-empty amount, split like the employee, dated the last day of the month (or today
	 * for the current month). Returns how many were recorded.
	 */
	public int runPayroll(YearMonth month, List<Long> employeeIds, List<String> amounts, String paidFrom, Actor actor)
	{
		requireAdmin(actor);
		LocalDate today = LocalDate.now(clock);
		if (month.isAfter(YearMonth.from(today)))
		{
			throw new StaffException("Salaries cannot be recorded for a future month.");
		}
		if (!List.of("RECEPTION", "SAFE", "BANK", "RESTAURANT_BANK").contains(paidFrom))
		{
			throw new StaffException("Choose where the salaries were paid from.");
		}
		LocalDate date = month.equals(YearMonth.from(today)) ? today : month.atEndOfMonth();
		String monthLabel = month.getMonth().getDisplayName(TextStyle.FULL, Locale.FRENCH) + " " + month.getYear();

		List<Object[]> toRecord = new ArrayList<>();
		for (int i = 0; i < employeeIds.size(); i++)
		{
			String text = i < amounts.size() ? amounts.get(i) : null;
			if (text == null || text.isBlank())
			{
				continue;
			}
			Double amount = Money.parse(text);
			Employee e = employees.find(employeeIds.get(i)).orElseThrow(() -> new StaffException("Employee not found."));
			if (!Money.isValidAmount(amount))
			{
				throw new StaffException("Check the amount for " + e.name() + ".");
			}
			if (expenses.salaryPaid(e.id(), month) > 0)
			{
				throw new StaffException(e.name() + " already has a salary recorded for " + monthLabel + ". Remove it from the expenses first.");
			}
			toRecord.add(new Object[]{e, amount});
		}
		if (toRecord.isEmpty())
		{
			throw new StaffException("Enter at least one salary amount.");
		}

		String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
		return tx.execute(s -> {
			int n = 0;
			for (Object[] pair : toRecord)
			{
				Employee e = (Employee) pair[0];
				double amount = (Double) pair[1];
				long id = expenses.insert(new ExpenseDraft(date, "Salaries", "Salaire " + monthLabel + " — " + e.name(), amount,
						ExpenseModels.isBank(paidFrom) ? "TRANSFER" : "CASH", mainActivity(e), paidFrom, "APPROVED",
						actor.displayName(), actor.displayName(), now, actor.id(), e.id()));
				allocations.replace(id, allocationFor(e));
				audit.record(actor, "PAYROLL", "expense", id, e.name() + " " + monthLabel + " " + String.format(Locale.US, "%,.2f", amount)
						+ " from " + paidFrom + " " + describe(e.splits()));
				n++;
			}
			return n;
		});
	}

	/* ======================= checks ======================= */

	private String checkedName(String text, Long selfId)
	{
		String name = text == null ? "" : text.trim().replaceAll("\\s+", " ");
		if (name.length() < 2 || name.length() > 60)
		{
			throw new StaffException("Enter the employee's name (2 to 60 characters).");
		}
		Optional<Employee> same = employees.findByName(name);
		if (same.isPresent() && (selfId == null || same.get().id() != selfId))
		{
			throw new StaffException("“" + name + "” already exists.");
		}
		return name;
	}

	private static String job(String text)
	{
		if (text == null || text.isBlank())
		{
			return null;
		}
		String j = text.trim().replaceAll("\\s+", " ");
		return j.length() > 60 ? j.substring(0, 60) : j;
	}

	private static Double checkedSalary(String text)
	{
		if (text == null || text.isBlank())
		{
			return null;
		}
		Double v = Money.parse(text);
		if (!Money.isValidAmount(v))
		{
			throw new StaffException("The monthly salary must be a positive amount, or left empty.");
		}
		return v;
	}

	/** Same rules as expense splits, but a single activity at 100% is kept (it is the employee's activity). */
	private List<Split> checkedSplits(List<String> activities, List<String> percents)
	{
		List<Split> splits = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		double total = 0;
		int rows = Math.max(activities == null ? 0 : activities.size(), percents == null ? 0 : percents.size());
		for (int i = 0; i < rows; i++)
		{
			String name = activities != null && i < activities.size() && activities.get(i) != null ? activities.get(i).trim() : "";
			String pctText = percents != null && i < percents.size() && percents.get(i) != null ? percents.get(i).trim() : "";
			if (name.isEmpty() && pctText.isEmpty())
			{
				continue;
			}
			Optional<Option> activity = options.findActivity(name);
			if (activity.isEmpty())
			{
				throw new StaffException("Choose an activity on each line you use.");
			}
			double pct;
			try
			{
				pct = pctText.isEmpty() ? 100 : Double.parseDouble(pctText.replace("%", "").replace(",", ".").trim());
			}
			catch (NumberFormatException e)
			{
				throw new StaffException("Give “" + activity.get().name() + "” a percentage.");
			}
			if (pct <= 0 || pct > 100)
			{
				throw new StaffException("Percentages are between 0 and 100.");
			}
			if (!seen.add(activity.get().name().toLowerCase(Locale.ROOT)))
			{
				throw new StaffException("“" + activity.get().name() + "” appears twice.");
			}
			splits.add(new Split(activity.get().name(), Math.round(pct * 100) / 100.0));
			total += pct;
		}
		if (splits.isEmpty())
		{
			throw new StaffException("Choose at least one activity for this employee.");
		}
		if (Math.abs(total - 100) > 0.01)
		{
			throw new StaffException("The percentages add up to " + String.format(Locale.ROOT, "%.2f", total).replaceAll("\\.?0+$", "") + "%, they must add up to 100%.");
		}
		splits.sort(java.util.Comparator.comparingDouble(Split::percent).reversed());
		return splits;
	}

	private static String describe(List<Split> splits)
	{
		StringBuilder sb = new StringBuilder("[");
		for (Split s : splits)
		{
			if (sb.length() > 1)
			{
				sb.append(", ");
			}
			sb.append(s.activity()).append(' ').append(s.percent()).append('%');
		}
		return sb.append(']').toString();
	}

	private static void requireAdmin(Actor actor)
	{
		if (!actor.isAdmin())
		{
			throw new StaffException("Only an admin can manage staff.");
		}
	}
}
