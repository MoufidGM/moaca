package com.cslsm.web.staff;

import com.cslsm.web.expenses.ExpenseOptionRepository;
import com.cslsm.web.staff.EmployeeRepository.Employee;
import com.cslsm.web.staff.EmployeeService.EmployeeInput;
import com.cslsm.web.staff.EmployeeService.StaffException;
import com.cslsm.web.support.CurrentActor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Controller
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class StaffController
{
	private static final int SPLIT_ROWS = 4;

	private final EmployeeRepository employees;
	private final EmployeeService service;
	private final ExpenseOptionRepository options;
	private final CurrentActor currentActor;
	private final Clock clock;

	public StaffController(EmployeeRepository employees, EmployeeService service, ExpenseOptionRepository options,
						   CurrentActor currentActor, Clock clock)
	{
		this.employees = employees;
		this.service = service;
		this.options = options;
		this.currentActor = currentActor;
		this.clock = clock;
	}

	/* ======================= employees ======================= */

	@GetMapping("/employees")
	public String list(Model model)
	{
		model.addAttribute("employees", employees.all(true));
		return "employees";
	}

	@GetMapping("/employees/new")
	public String newForm(Model model)
	{
		return form(model, null, new EmployeeInput("", "", "", true, List.of(), List.of()), null);
	}

	@GetMapping("/employees/{id}")
	public String editForm(@PathVariable long id, Model model)
	{
		Employee e = employees.find(id).orElseThrow(() -> new StaffException("Employee not found."));
		List<String> acts = new ArrayList<>();
		List<String> pcts = new ArrayList<>();
		e.splits().forEach(s -> {
			acts.add(s.activity());
			pcts.add(String.format(Locale.ROOT, "%.2f", s.percent()).replaceAll("\\.?0+$", ""));
		});
		String salary = e.monthlySalary() == null ? "" : String.format(Locale.ROOT, "%.2f", e.monthlySalary()).replaceAll("\\.?0+$", "");
		return form(model, e, new EmployeeInput(e.name(), e.job(), salary, e.active(), acts, pcts), null);
	}

	@PostMapping("/employees/new")
	public String create(@RequestParam String name, @RequestParam(defaultValue = "") String job,
						 @RequestParam(defaultValue = "") String salary,
						 @RequestParam(name = "splitActivity", required = false) List<String> splitActivity,
						 @RequestParam(name = "splitPercent", required = false) List<String> splitPercent,
						 Model model, RedirectAttributes redirect)
	{
		EmployeeInput in = new EmployeeInput(name, job, salary, true, splitActivity, splitPercent);
		try
		{
			service.create(in, currentActor.require());
			redirect.addFlashAttribute("flashOk", "Employee added.");
			return "redirect:/employees";
		}
		catch (StaffException e)
		{
			return form(model, null, in, e.getMessage());
		}
	}

	@PostMapping("/employees/{id}")
	public String update(@PathVariable long id, @RequestParam String name, @RequestParam(defaultValue = "") String job,
						 @RequestParam(defaultValue = "") String salary, @RequestParam(defaultValue = "false") boolean active,
						 @RequestParam(name = "splitActivity", required = false) List<String> splitActivity,
						 @RequestParam(name = "splitPercent", required = false) List<String> splitPercent,
						 Model model, RedirectAttributes redirect)
	{
		EmployeeInput in = new EmployeeInput(name, job, salary, active, splitActivity, splitPercent);
		try
		{
			service.update(id, in, currentActor.require());
			redirect.addFlashAttribute("flashOk", "Saved.");
			return "redirect:/employees";
		}
		catch (StaffException e)
		{
			return form(model, employees.find(id).orElse(null), in, e.getMessage());
		}
	}

	private String form(Model model, Employee existing, EmployeeInput in, String error)
	{
		List<String[]> rows = new ArrayList<>();
		int n = Math.max(SPLIT_ROWS, in.splitActivity() == null ? 0 : in.splitActivity().size());
		for (int i = 0; i < n; i++)
		{
			String a = in.splitActivity() != null && i < in.splitActivity().size() ? in.splitActivity().get(i) : "";
			String p = in.splitPercent() != null && i < in.splitPercent().size() ? in.splitPercent().get(i) : "";
			rows.add(new String[]{a == null ? "" : a, p == null ? "" : p});
		}
		model.addAttribute("existing", existing);
		model.addAttribute("in", in);
		model.addAttribute("splitRows", rows);
		model.addAttribute("activities", options.activities());
		model.addAttribute("error", error);
		model.addAttribute("pageTitle", existing == null ? "New employee" : "Edit employee");
		return "employee-form";
	}

	/* ======================= payroll ======================= */

	@GetMapping("/payroll")
	public String payroll(@RequestParam(required = false) String month, Model model)
	{
		YearMonth ym = month(month);
		model.addAttribute("month", ym.toString());
		model.addAttribute("monthLabel", ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear());
		model.addAttribute("prevMonth", ym.minusMonths(1).toString());
		model.addAttribute("nextMonth", ym.plusMonths(1).toString());
		model.addAttribute("lines", service.payrollLines(ym));
		return "payroll";
	}

	@PostMapping("/payroll")
	public String runPayroll(@RequestParam String month, @RequestParam(name = "employeeId") List<Long> employeeIds,
							 @RequestParam(name = "amount") List<String> amounts, @RequestParam(defaultValue = "BANK") String paidFrom,
							 RedirectAttributes redirect)
	{
		YearMonth ym = month(month);
		int n = service.runPayroll(ym, employeeIds, amounts, paidFrom, currentActor.require());
		redirect.addFlashAttribute("flashOk", n + " salary expense(s) recorded for " + ym + ".");
		return "redirect:/payroll?month=" + ym;
	}

	private YearMonth month(String text)
	{
		try
		{
			return text == null || text.isBlank() ? YearMonth.from(LocalDate.now(clock)) : YearMonth.parse(text.trim());
		}
		catch (DateTimeParseException e)
		{
			return YearMonth.from(LocalDate.now(clock));
		}
	}

	@ExceptionHandler(StaffException.class)
	public String rule(StaffException e, jakarta.servlet.http.HttpServletRequest request, RedirectAttributes redirect)
	{
		redirect.addFlashAttribute("flashError", e.getMessage());
		return "redirect:" + (request.getRequestURI().startsWith("/payroll") ? "/payroll" : "/employees");
	}
}
