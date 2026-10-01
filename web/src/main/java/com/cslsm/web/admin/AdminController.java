package com.cslsm.web.admin;

import com.cslsm.web.admin.AdminService.AdminException;
import com.cslsm.web.admin.AdminService.UserInput;
import com.cslsm.web.admin.AuditLogRepository.Filter;
import com.cslsm.web.expenses.ExpensePresetRepository;
import com.cslsm.web.finance.FinanceRepository;
import com.cslsm.web.security.AppUser;
import com.cslsm.web.security.Role;
import com.cslsm.web.security.UserRepository;
import com.cslsm.web.support.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Administration: accounts, expense categories, settings and the audit log. Super admin only. */
@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminController
{
	private static final int AUDIT_PAGE_SIZE = 100;

	private final AdminService admin;
	private final UserRepository users;
	private final CategoryRepository categories;
	private final AuditLogRepository auditLog;
	private final FinanceRepository finance;
	private final CurrentActor currentActor;
	private final ExpensePresetRepository presets;

	public AdminController(AdminService admin, UserRepository users, CategoryRepository categories, AuditLogRepository auditLog,
						   FinanceRepository finance, CurrentActor currentActor, ExpensePresetRepository presets)
	{
		this.presets = presets;
		this.admin = admin;
		this.users = users;
		this.categories = categories;
		this.auditLog = auditLog;
		this.finance = finance;
		this.currentActor = currentActor;
	}

	@GetMapping
	public String page(Model model)
	{
		model.addAttribute("users", users.all());
		model.addAttribute("now", System.currentTimeMillis());
		model.addAttribute("categories", categories.all());
		model.addAttribute("headings", AdminService.HEADINGS);
		model.addAttribute("resetEachYear", finance.resetEachYear());
		model.addAttribute("presets", presets.all());
		model.addAttribute("me", currentActor.require().id());
		return "admin";
	}

	/* ======================= accounts ======================= */

	@GetMapping("/users/new")
	public String newUser(Model model)
	{
		return userForm(model, null, new UserInput("", "", "RECEPTIONIST", true, "", ""), null);
	}

	@PostMapping("/users/new")
	public String createUser(@RequestParam(defaultValue = "") String username, @RequestParam(defaultValue = "") String displayName,
							 @RequestParam(defaultValue = "") String role, @RequestParam(defaultValue = "") String password,
							 @RequestParam(defaultValue = "") String repeat, Model model, RedirectAttributes redirect)
	{
		UserInput in = new UserInput(username, displayName, role, true, password, repeat);
		try
		{
			long id = admin.createUser(in, currentActor.require());
			redirect.addFlashAttribute("flashOk", "Account “" + username.trim() + "” created. Give the temporary password to the person: "
					+ "they will choose their own at the first sign-in"
					+ (Role.valueOf(role.trim().toUpperCase(Locale.ROOT)).requiresTwoFactor() ? ", then scan the two-factor QR code." : "."));
			return "redirect:/admin/users/" + id;
		}
		catch (AdminException e)
		{
			return userForm(model, null, new UserInput(username, displayName, role, true, "", ""), e.getMessage());
		}
	}

	@GetMapping("/users/{id}")
	public String editUser(@PathVariable long id, Model model)
	{
		AppUser u = users.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
		return userForm(model, u, new UserInput(u.username(), u.displayName(), u.role().name(), u.enabled(), "", ""), null);
	}

	@PostMapping("/users/{id}")
	public String updateUser(@PathVariable long id, @RequestParam(defaultValue = "") String displayName,
							 @RequestParam(defaultValue = "") String role, @RequestParam(defaultValue = "false") boolean enabled,
							 Model model, RedirectAttributes redirect)
	{
		try
		{
			admin.updateUser(id, displayName, role, enabled, currentActor.require());
			redirect.addFlashAttribute("flashOk", "Saved. A changed role or a switched-off account takes effect at the person's next click.");
			return "redirect:/admin";
		}
		catch (AdminException e)
		{
			AppUser u = users.findById(id).orElse(null);
			return userForm(model, u, new UserInput(u == null ? "" : u.username(), displayName, role, enabled, "", ""), e.getMessage());
		}
	}

	@PostMapping("/users/{id}/password")
	public String resetPassword(@PathVariable long id, @RequestParam(defaultValue = "") String password,
								@RequestParam(defaultValue = "") String repeat, RedirectAttributes redirect)
	{
		admin.resetPassword(id, password, repeat, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Temporary password set and account unlocked. The person will choose their own password at the next sign-in.");
		return "redirect:/admin/users/" + id;
	}

	@PostMapping("/users/{id}/2fa-reset")
	public String resetTwoFactor(@PathVariable long id, RedirectAttributes redirect)
	{
		admin.resetTwoFactor(id, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Two-factor reset. The person will scan a new QR code at the next sign-in.");
		return "redirect:/admin/users/" + id;
	}

	@PostMapping("/users/{id}/unlock")
	public String unlock(@PathVariable long id, RedirectAttributes redirect)
	{
		admin.unlock(id, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Account unlocked.");
		return "redirect:/admin/users/" + id;
	}

	private String userForm(Model model, AppUser existing, UserInput in, String error)
	{
		model.addAttribute("existing", existing);
		model.addAttribute("in", in);
		model.addAttribute("roles", Role.values());
		model.addAttribute("error", error);
		model.addAttribute("now", System.currentTimeMillis());
		model.addAttribute("self", existing != null && existing.id() == currentActor.require().id());
		model.addAttribute("pageTitle", existing == null ? "New account" : "Account “" + existing.username() + "”");
		return "admin-user";
	}

	/* ======================= categories ======================= */

	@PostMapping("/categories/new")
	public String createCategory(@RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String heading,
								 @RequestParam(defaultValue = "false") boolean adminOnly, @RequestParam(defaultValue = "") String sortOrder,
								 RedirectAttributes redirect)
	{
		admin.createCategory(name, heading, adminOnly, sortOrder, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Category added. It is offered on the expense form right away.");
		return "redirect:/admin#categories";
	}

	@PostMapping("/categories/{id}")
	public String updateCategory(@PathVariable long id, @RequestParam(defaultValue = "") String heading,
								 @RequestParam(defaultValue = "false") boolean adminOnly, @RequestParam(defaultValue = "false") boolean active,
								 @RequestParam(defaultValue = "") String sortOrder, RedirectAttributes redirect)
	{
		admin.updateCategory(id, heading, adminOnly, active, sortOrder, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Category saved.");
		return "redirect:/admin#categories";
	}

	/* ======================= frequent expenses ======================= */

	@PostMapping("/presets/{id}")
	public String preset(@PathVariable long id, @RequestParam(defaultValue = "false") boolean active, RedirectAttributes redirect)
	{
		ExpensePresetRepository.Preset p = presets.find(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
		presets.setActive(id, active);
		admin.audit(currentActor.require(), "EXPENSE_PRESET_UPDATE", "expense_preset", id, p.name() + (active ? " on" : " off"));
		redirect.addFlashAttribute("flashOk", "Saved.");
		return "redirect:/admin#presets";
	}

	/* ======================= settings ======================= */

	@PostMapping("/settings/year-mode")
	public String yearMode(@RequestParam(defaultValue = "CARRY") String mode, RedirectAttributes redirect)
	{
		admin.setResetEachYear("RESET".equalsIgnoreCase(mode), currentActor.require());
		redirect.addFlashAttribute("flashOk", "Setting saved. The reception balance on the Dashboard and Cash & Reserves follows it right away.");
		return "redirect:/admin#settings";
	}

	/* ======================= audit log ======================= */

	@GetMapping("/audit")
	public String audit(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
						@RequestParam(required = false) String user, @RequestParam(required = false) String action,
						@RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page, Model model)
	{
		Filter filter = new Filter(date(from), date(to), user, action, q);
		int total = auditLog.count(filter);
		int pages = Math.max(1, (total + AUDIT_PAGE_SIZE - 1) / AUDIT_PAGE_SIZE);
		int p = Math.min(Math.max(1, page), pages);
		model.addAttribute("entries", auditLog.search(filter, AUDIT_PAGE_SIZE, (p - 1) * AUDIT_PAGE_SIZE));
		model.addAttribute("total", total);
		model.addAttribute("page", p);
		model.addAttribute("pages", pages);
		model.addAttribute("from", from);
		model.addAttribute("to", to);
		model.addAttribute("user", user);
		model.addAttribute("action", action);
		model.addAttribute("q", q);
		model.addAttribute("usernames", auditLog.usernames());
		model.addAttribute("actions", auditLog.actions());
		return "admin-audit";
	}

	private static LocalDate date(String text)
	{
		try
		{
			return text == null || text.isBlank() ? null : LocalDate.parse(text.trim());
		}
		catch (DateTimeParseException e)
		{
			return null;
		}
	}

	@ExceptionHandler(AdminException.class)
	public String rule(AdminException e, HttpServletRequest request, RedirectAttributes redirect)
	{
		redirect.addFlashAttribute("flashError", e.getMessage());
		String uri = request.getRequestURI();
		// Actions on one account go back to that account's page
		if (uri.matches(".*/admin/users/\\d+(/.*)?"))
		{
			return "redirect:" + uri.replaceAll("(/admin/users/\\d+).*", "$1");
		}
		return "redirect:/admin";
	}
}
