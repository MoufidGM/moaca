package com.cslsm.web.expenses;

import com.cslsm.web.expenses.AttachmentRepository.Attachment;
import com.cslsm.web.expenses.ExpenseModels.ExpenseForm;
import com.cslsm.web.expenses.ExpenseModels.ExpenseQuery;
import com.cslsm.web.expenses.ExpenseModels.ExpenseRow;
import com.cslsm.web.expenses.ExpenseModels.ExpenseRuleException;
import com.cslsm.web.expenses.ExpenseModels.ExpenseSearch;
import com.cslsm.web.support.Actor;
import com.cslsm.web.support.AuditService;
import com.cslsm.web.support.CurrentActor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
@RequestMapping("/expenses")
@PreAuthorize("hasAnyRole('RECEPTIONIST', 'RESTAURANT_MANAGER', 'ADMIN', 'SUPER_ADMIN')")
public class ExpenseController
{
	/** How far back a receptionist sees her own entries. */
	private static final int RECEPTION_HISTORY_DAYS = 60;

	private final ExpenseService service;
	private final ExpenseRepository repo;
	private final ExpenseOptionRepository options;
	private final AttachmentRepository attachments;
	private final AttachmentService attachmentService;
	private final com.cslsm.web.staff.EmployeeRepository employees;
	private final CurrentActor currentActor;
	private final ExpensePresetRepository presets;
	private final AuditService audit;
	private final Clock clock;

	public ExpenseController(ExpenseService service, ExpenseRepository repo, ExpenseOptionRepository options,
							 AttachmentRepository attachments, AttachmentService attachmentService,
							 com.cslsm.web.staff.EmployeeRepository employees,
							 CurrentActor currentActor, Clock clock,
							 ExpensePresetRepository presets, AuditService audit)
	{
		this.service = service;
		this.repo = repo;
		this.options = options;
		this.attachments = attachments;
		this.attachmentService = attachmentService;
		this.employees = employees;
		this.currentActor = currentActor;
		this.presets = presets;
		this.audit = audit;
		this.clock = clock;
	}

	/* ======================= list ======================= */

	@GetMapping
	public String list(@ModelAttribute("query") ExpenseQuery query, Model model)
	{
		Actor actor = currentActor.require();
		LocalDate today = LocalDate.now(clock);
		YearMonth month = parseMonth(query.getMonth(), today);
		query.setMonth(month.toString());

		String status = oneOf(query.getStatus(), List.of("PENDING", "APPROVED", "REJECTED"));
		ExpenseSearch search;
		if (actor.isAdmin())
		{
			// Pending entries and suspected bank deposits are shown whatever the month.
			boolean allMonths = "PENDING".equals(status) || query.isSuspects();
			search = new ExpenseSearch(allMonths ? null : month.atDay(1), allMonths ? null : month.atEndOfMonth(),
					status, blankToNull(query.getCategory()), blankToNull(query.getActivity()),
					oneOf(query.getPaidFrom(), ExpenseModels.PAID_FROM), blankToNull(query.getQ()), null, query.isSuspects());
			model.addAttribute("pending", repo.pending());
			model.addAttribute("suspects", repo.suspectedBankDeposits());
			model.addAttribute("categoriesInUse", repo.categoriesInUse());
			model.addAttribute("activitiesInUse", repo.activitiesInUse());
		}
		else
		{
			search = new ExpenseSearch(today.minusDays(RECEPTION_HISTORY_DAYS), null, status, null, null, null,
					blankToNull(query.getQ()), actor.id(), false);
		}

		List<ExpenseRow> rows = repo.search(search);
		model.addAttribute("rows", rows);
		model.addAttribute("total", rows.stream().filter(r -> !r.isRejected()).mapToDouble(ExpenseRow::amount).sum());
		model.addAttribute("isAdmin", actor.isAdmin());
		model.addAttribute("monthLabel", month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear());
		model.addAttribute("prevMonth", month.minusMonths(1).toString());
		model.addAttribute("nextMonth", month.plusMonths(1).toString());
		model.addAttribute("paidFromOptions", ExpenseModels.PAID_FROM);
		return "expenses";
	}

	/* ======================= create ======================= */

	@GetMapping("/new")
	public String newForm(@RequestParam(required = false) Long preset, Model model)
	{
		ExpenseForm form = new ExpenseForm();
		form.setDate(LocalDate.now(clock).toString());
		if (preset != null)
		{
			Actor actor = currentActor.require();
			presets.find(preset).filter(ExpensePresetRepository.Preset::active).ifPresent(pr -> {
				form.setCategory(pr.category());
				form.setActivity(pr.activity());
				form.setDescription(pr.name());
				if (pr.amount() != null)
				{
					form.setAmount(String.format(Locale.ROOT, "%.2f", pr.amount()).replaceAll("\\.00$", ""));
				}
				if (actor.isAdmin())
				{
					form.setPaidFrom(pr.paidFrom());
				}
			});
		}
		return form(model, form, null, null);
	}

	/** Admins turn an expense into a "frequent expense" for the form's one-tap list. */
	@PostMapping("/{id}/preset")
	@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
	public String saveAsPreset(@PathVariable long id, @RequestParam(defaultValue = "") String name, RedirectAttributes redirect)
	{
		Actor actor = currentActor.require();
		ExpenseRow e = repo.find(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
		String n = (name.isBlank() ? (e.description() == null ? e.category() : e.description()) : name).trim();
		n = n.length() > 60 ? n.substring(0, 60) : n;
		if (presets.findByName(n).isPresent())
		{
			redirect.addFlashAttribute("flashError", "There is already a frequent expense named “" + n + "”.");
			return "redirect:/expenses/" + id;
		}
		presets.insert(n, e.category(), e.activity() == null ? "General" : e.activity(), e.amount(), e.paidFrom());
		audit.record(actor, "EXPENSE_PRESET_CREATE", "expense_preset", null, n + " (" + e.category() + ", " + e.activity() + ")");
		redirect.addFlashAttribute("flashOk", "“" + n + "” is now a frequent expense: one tap on the expense form.");
		return "redirect:/expenses/" + id;
	}

	@PostMapping("/new")
	public String create(@ModelAttribute("form") ExpenseForm form,
						 @RequestParam(name = "receipts", required = false) List<MultipartFile> receipts,
						 Model model, RedirectAttributes redirect)
	{
		Actor actor = currentActor.require();
		try
		{
			ExpenseService.Saved saved = service.create(form, receipts, actor);
			redirect.addFlashAttribute("flashOk", actor.isAdmin()
					? "Expense saved."
					: "Expense saved. It will count once an admin approves it.");
			redirect.addFlashAttribute("flashInfo", saved.notice());
			return "redirect:/expenses/" + saved.id();
		}
		catch (ExpenseRuleException e)
		{
			return form(model, form, null, e.getMessage());
		}
	}

	/* ======================= view / edit / delete ======================= */

	@GetMapping("/{id}")
	public String detail(@PathVariable long id, Model model)
	{
		Actor actor = currentActor.require();
		ExpenseRow e = service.visible(id, actor);
		model.addAttribute("e", e);
		model.addAttribute("splits", service.splitsOf(id));
		model.addAttribute("attachments", attachments.forExpense(id));
		model.addAttribute("canEdit", service.canEdit(e, actor));
		model.addAttribute("isAdmin", actor.isAdmin());
		return "expense-detail";
	}

	@GetMapping("/{id}/edit")
	public String editForm(@PathVariable long id, Model model)
	{
		Actor actor = currentActor.require();
		ExpenseRow e = service.visible(id, actor);
		if (!service.canEdit(e, actor))
		{
			return "redirect:/expenses/" + id;
		}
		return form(model, ExpenseForm.of(e, service.splitsOf(id)), e, null);
	}

	@PostMapping("/{id}/edit")
	public String update(@PathVariable long id, @ModelAttribute("form") ExpenseForm form, Model model,
						 RedirectAttributes redirect)
	{
		Actor actor = currentActor.require();
		try
		{
			ExpenseService.Saved saved = service.update(id, form, actor);
			redirect.addFlashAttribute("flashOk", "Changes saved.");
			redirect.addFlashAttribute("flashInfo", saved.notice());
			return "redirect:/expenses/" + id;
		}
		catch (ExpenseRuleException e)
		{
			return form(model, form, service.visible(id, actor), e.getMessage());
		}
	}

	@PostMapping("/{id}/delete")
	public String delete(@PathVariable long id, RedirectAttributes redirect)
	{
		service.delete(id, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Expense #" + id + " deleted.");
		return "redirect:/expenses";
	}

	/* ======================= approval (admin) ======================= */

	@PostMapping("/{id}/approve")
	@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
	public String approve(@PathVariable long id, RedirectAttributes redirect)
	{
		int n = service.approve(List.of(id), currentActor.require());
		redirect.addFlashAttribute(n == 1 ? "flashOk" : "flashError", n == 1 ? "Approved." : "This expense is no longer pending.");
		return "redirect:/expenses/" + id;
	}

	@PostMapping("/approve")
	@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
	public String approveSelected(@RequestParam(name = "ids", required = false) Long[] ids, RedirectAttributes redirect)
	{
		if (ids == null || ids.length == 0)
		{
			redirect.addFlashAttribute("flashError", "Tick the expenses to approve first.");
			return "redirect:/expenses?status=PENDING";
		}
		int n = service.approve(Arrays.asList(ids), currentActor.require());
		redirect.addFlashAttribute("flashOk", n + " expense(s) approved.");
		return "redirect:/expenses?status=PENDING";
	}

	@PostMapping("/{id}/reject")
	@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
	public String reject(@PathVariable long id, @RequestParam(defaultValue = "") String reason, RedirectAttributes redirect)
	{
		service.reject(id, reason, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Rejected.");
		return "redirect:/expenses/" + id;
	}

	@PostMapping("/{id}/bank-deposit")
	@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
	public String toBankDeposit(@PathVariable long id, RedirectAttributes redirect)
	{
		service.reclassifyAsBankDeposit(id, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Moved to bank deposits. It no longer counts as an expense; the balance is unchanged.");
		return "redirect:/expenses/" + id;
	}

	/* ======================= receipts ======================= */

	@PostMapping("/{id}/receipts")
	public String addReceipts(@PathVariable long id,
							  @RequestParam(name = "receipts", required = false) List<MultipartFile> receipts,
							  RedirectAttributes redirect)
	{
		service.addReceipts(id, receipts, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Receipt added.");
		return "redirect:/expenses/" + id;
	}

	@GetMapping("/receipts/{attachmentId}")
	public ResponseEntity<byte[]> receipt(@PathVariable long attachmentId,
										  @RequestParam(defaultValue = "false") boolean download) throws IOException
	{
		Attachment a = service.visibleAttachment(attachmentId, currentActor.require());
		byte[] content = attachmentService.content(a);
		ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
				.filename(a.originalName(), StandardCharsets.UTF_8).build();
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
				.cacheControl(CacheControl.noStore())
				.contentType(MediaType.parseMediaType(a.contentType()))
				.body(content);
	}

	@PostMapping("/receipts/{attachmentId}/delete")
	public String deleteReceipt(@PathVariable long attachmentId, RedirectAttributes redirect)
	{
		long expenseId = service.deleteReceipt(attachmentId, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Receipt removed.");
		return "redirect:/expenses/" + expenseId;
	}

	/* ======================= errors ======================= */

	@ExceptionHandler(ExpenseService.NotFound.class)
	@ResponseStatus(NOT_FOUND)
	public String notFound()
	{
		return "error/404";
	}

	/** Rule violations outside a form (approve, reject, delete…): back to the page with the message. */
	@ExceptionHandler(ExpenseRuleException.class)
	public String rule(ExpenseRuleException e, jakarta.servlet.http.HttpServletRequest request, RedirectAttributes redirect)
	{
		redirect.addFlashAttribute("flashError", e.getMessage());
		String path = request.getRequestURI().replaceFirst("/(approve|reject|delete|bank-deposit|receipts)$", "");
		return "redirect:" + (path.matches("/expenses/\\d+") ? path : "/expenses");
	}

	/* ======================= helpers ======================= */

	private String form(Model model, ExpenseForm form, ExpenseRow existing, String error)
	{
		Actor actor = currentActor.require();
		model.addAttribute("form", form);
		model.addAttribute("existing", existing);
		model.addAttribute("pageTitle", existing == null ? "New expense" : "Edit expense");
		model.addAttribute("error", error);
		model.addAttribute("isAdmin", actor.isAdmin());
		model.addAttribute("categories", options.categories(actor.isAdmin()));
		model.addAttribute("employees", employees.all(false));
		model.addAttribute("isRestaurant", actor.isRestaurant());
		model.addAttribute("presets", existing == null ? presets.forUser(actor.isAdmin()) : List.<ExpensePresetRepository.Preset>of());
		List<ExpenseModels.Option> activities = options.activities();
		model.addAttribute("activities", activities);
		java.util.Map<String, List<ExpenseModels.Option>> groups = new java.util.LinkedHashMap<>();
		groups.put("Activities", activities.stream().filter(o -> !o.isShared() && ("REVENUE".equals(o.costRule()) || "PART_OF".equals(o.costRule()))).toList());
		groups.put("Shared by all activities", activities.stream().filter(ExpenseModels.Option::isShared).toList());
		groups.put("Other", activities.stream().filter(o -> "SEPARATE".equals(o.costRule())).toList());
		groups.values().removeIf(List::isEmpty);
		model.addAttribute("activityGroups", groups);
		model.addAttribute("paymentMethods", ExpenseModels.PAYMENT_METHODS);
		model.addAttribute("paidFromOptions", ExpenseModels.PAID_FROM);
		LocalDate today = LocalDate.now(clock);
		model.addAttribute("maxDate", today.toString());
		model.addAttribute("minDate", actor.isAdmin() ? "2020-01-01" : today.minusDays(ExpenseService.RECEPTION_MAX_DAYS_BACK).toString());
		return "expense-form";
	}

	private static YearMonth parseMonth(String raw, LocalDate today)
	{
		try
		{
			return raw == null || raw.isBlank() ? YearMonth.from(today) : YearMonth.parse(raw.trim());
		}
		catch (DateTimeParseException e)
		{
			return YearMonth.from(today);
		}
	}

	private static String oneOf(String value, List<String> allowed)
	{
		if (value == null)
		{
			return null;
		}
		String v = value.trim().toUpperCase(Locale.ROOT);
		return allowed.contains(v) ? v : null;
	}

	private static String blankToNull(String s)
	{
		return s == null || s.isBlank() ? null : s.trim();
	}
}
