package com.cslsm.web.expenses;

import com.cslsm.web.expenses.AttachmentRepository.Attachment;
import com.cslsm.web.expenses.ExpenseModels.ExpenseDraft;
import com.cslsm.web.expenses.ExpenseModels.ExpenseForm;
import com.cslsm.web.expenses.ExpenseModels.ExpenseRow;
import com.cslsm.web.expenses.ExpenseModels.ExpenseRuleException;
import com.cslsm.web.expenses.ExpenseModels.Option;
import com.cslsm.web.expenses.ExpenseModels.Split;
import com.cslsm.web.reserves.MovementRepository;
import com.cslsm.web.staff.EmployeeRepository;
import com.cslsm.web.support.Actor;
import com.cslsm.web.support.AuditService;
import com.cslsm.web.support.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Expense rules.
 *
 *   Receptionist: enters day-to-day expenses (non admin-only categories), dated today or up to
 *                 7 days back, always paid from the reception. Entries are PENDING until an admin
 *                 approves them. Sees and can change only her own pending entries.
 *   Admin:        any category, any past date, paid from reception, safe or bank. Entries are
 *                 approved immediately. Approves / rejects pending entries, edits and deletes any.
 *
 * Nobody can date an expense in the future (the 2027 / 2028 typos).
 *
 * Admins can split one expense across several activities (percentages totalling 100%), e.g.
 * an electricity bill shared by the terrains and the gym. The per-activity analysis uses the
 * split; lists show the expense under its largest share.
 */
@Service
public class ExpenseService
{
	static final int RECEPTION_MAX_DAYS_BACK = 7;
	public static final String RESTAURANT_ACTIVITY = "Tiki Taka";
	private static final LocalDate EARLIEST = LocalDate.of(2020, 1, 1);

	private final ExpenseRepository expenses;
	private final ExpenseOptionRepository options;
	private final AttachmentRepository attachmentRepo;
	private final AttachmentService attachments;
	private final AllocationRepository allocations;
	private final MovementRepository movements;
	private final EmployeeRepository employees;
	private final AuditService audit;
	private final TransactionTemplate tx;
	private final Clock clock;

	public ExpenseService(ExpenseRepository expenses, ExpenseOptionRepository options, AttachmentRepository attachmentRepo,
						  AttachmentService attachments, AllocationRepository allocations, MovementRepository movements,
						  EmployeeRepository employees, AuditService audit, TransactionTemplate tx, Clock clock)
	{
		this.expenses = expenses;
		this.options = options;
		this.attachmentRepo = attachmentRepo;
		this.attachments = attachments;
		this.allocations = allocations;
		this.movements = movements;
		this.employees = employees;
		this.audit = audit;
		this.tx = tx;
		this.clock = clock;
	}

	/** How an expense is split across activities (empty = 100% to its activity). */
	public List<Split> splitsOf(long expenseId)
	{
		return allocations.forExpense(expenseId);
	}

	/* ======================= permissions ======================= */

	public boolean canView(ExpenseRow e, Actor a)
	{
		return a.isAdmin() || (e.createdByUserId() != null && e.createdByUserId() == a.id());
	}

	public boolean canEdit(ExpenseRow e, Actor a)
	{
		if (e.isRejected())
		{
			return false;
		}
		return a.isAdmin() || (e.isPending() && e.createdByUserId() != null && e.createdByUserId() == a.id());
	}

	public ExpenseRow visible(long id, Actor a)
	{
		return expenses.find(id).filter(e -> canView(e, a))
				.orElseThrow(() -> new NotFound("Expense not found."));
	}

	/** Shown as 404 so receptionists cannot probe which ids exist. */
	public static class NotFound extends RuntimeException
	{
		public NotFound(String message)
		{
			super(message);
		}
	}

	/* ======================= create / update / delete ======================= */

	public record Saved(long id, String notice)
	{
	}

	/** A checked form: what to write to expense, and how it is split (empty = not split). */
	private record Validated(ExpenseDraft draft, List<Split> splits)
	{
	}

	public Saved create(ExpenseForm form, List<MultipartFile> receipts, Actor actor)
	{
		Validated v = validate(form, actor, null);
		ExpenseDraft draft = v.draft();
		attachments.validate(receipts, 0);
		List<String> stored = new ArrayList<>();
		try
		{
			long id = tx.execute(s -> {
				long newId = expenses.insert(draft);
				if (!v.splits().isEmpty())
				{
					allocations.replace(newId, v.splits());
				}
				audit.record(actor, "EXPENSE_CREATE", "expense", newId, describe(draft) + describe(v.splits()));
				try
				{
					stored.addAll(attachments.store(newId, receipts, actor));
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
				return newId;
			});
			return new Saved(id, similarNotice(draft, id));
		}
		catch (RuntimeException e)
		{
			attachments.deleteStoredQuietly(stored);
			throw e;
		}
	}

	public Saved update(long id, ExpenseForm form, Actor actor)
	{
		ExpenseRow existing = visible(id, actor);
		if (!canEdit(existing, actor))
		{
			throw new ExpenseRuleException(existing.isRejected()
					? "Rejected expenses cannot be changed."
					: "Only pending expenses can be changed once entered. Ask an admin.");
		}
		Validated v = validate(form, actor, existing);
		ExpenseDraft draft = v.draft();
		List<Split> before = allocations.forExpense(id);
		tx.executeWithoutResult(s -> {
			expenses.update(id, draft);
			if (actor.isAdmin())
			{
				// Only admins can split; a receptionist's edit leaves an admin's split alone.
				allocations.replace(id, v.splits());
			}
			audit.record(actor, "EXPENSE_UPDATE", "expense", id, "before: " + describe(existing) + describe(before)
					+ " | after: " + describe(draft) + describe(actor.isAdmin() ? v.splits() : before));
		});
		return new Saved(id, similarNotice(draft, id));
	}

	public void delete(long id, Actor actor)
	{
		ExpenseRow existing = visible(id, actor);
		if (!actor.isAdmin() && !canEdit(existing, actor))
		{
			throw new ExpenseRuleException("Only pending expenses can be deleted. Ask an admin.");
		}
		List<Attachment> files = attachmentRepo.forExpense(id);
		tx.executeWithoutResult(s -> {
			attachmentRepo.deleteForExpense(id);
			allocations.deleteForExpense(id);
			expenses.delete(id);
			audit.record(actor, "EXPENSE_DELETE", "expense", id, describe(existing) + ", receipts=" + files.size());
		});
		attachments.deleteFiles(files);
	}

	/* ======================= approval ======================= */

	public int approve(List<Long> ids, Actor actor)
	{
		requireAdmin(actor);
		int[] count = {0};
		tx.executeWithoutResult(s -> {
			for (Long id : ids)
			{
				if (id != null && expenses.approve(id, actor.displayName()))
				{
					count[0]++;
					audit.record(actor, "EXPENSE_APPROVE", "expense", id, null);
				}
			}
		});
		return count[0];
	}

	public void reject(long id, String reason, Actor actor)
	{
		requireAdmin(actor);
		String why = reason == null ? "" : reason.trim();
		if (why.isEmpty())
		{
			throw new ExpenseRuleException("Give a reason so the receptionist knows what to fix.");
		}
		if (why.length() > 300)
		{
			why = why.substring(0, 300);
		}
		String finalWhy = why;
		tx.executeWithoutResult(s -> {
			if (!expenses.reject(id, actor.displayName(), finalWhy, false))
			{
				throw new ExpenseRuleException("Only pending expenses can be rejected.");
			}
			audit.record(actor, "EXPENSE_REJECT", "expense", id, finalWhy);
		});
	}

	/**
	 * A bank deposit recorded as an expense ("Versement au compte"): turns it into a
	 * "sent to bank" movement from wherever the cash was, and marks the expense rejected with
	 * a note, so it leaves the P&L but stays visible (with its receipt) for the record.
	 * The reception or safe balance is unchanged — the money left either way.
	 */
	public long reclassifyAsBankDeposit(long id, Actor actor)
	{
		requireAdmin(actor);
		ExpenseRow e = visible(id, actor);
		if (e.isRejected())
		{
			throw new ExpenseRuleException("This expense is already rejected.");
		}
		MovementRepository.Table table = switch (e.paidFrom())
		{
			case "RECEPTION", "RESTAURANT" -> MovementRepository.Table.RECEPTION;
			case "SAFE" -> MovementRepository.Table.SAFE;
			default -> throw new ExpenseRuleException("This was paid from the bank, so it cannot be a deposit to the bank.");
		};
		String till = "RESTAURANT".equals(e.paidFrom()) ? "RESTAURANT" : "RECEPTION";
		return tx.execute(s -> {
			String note = trimTo("Bank deposit (was expense #" + e.id() + ": " + e.description() + ")", 200);
			long movementId = movements.insert(table, e.date(), "BANK", e.amount(), note, till);
			expenses.reject(id, actor.displayName(),
					"Reclassified as a bank deposit (" + table.name().toLowerCase(Locale.ROOT) + " movement #" + movementId + ")", true);
			audit.record(actor, "EXPENSE_TO_BANK_DEPOSIT", "expense", id,
					describe(e) + " -> " + table.name() + " BANK movement #" + movementId);
			return movementId;
		});
	}

	/* ======================= receipts ======================= */

	public void addReceipts(long id, List<MultipartFile> files, Actor actor)
	{
		ExpenseRow e = visible(id, actor);
		if (!canEdit(e, actor))
		{
			throw new ExpenseRuleException("Receipts can only be added while you can still edit the expense.");
		}
		attachments.validate(files, attachmentRepo.countFor(id));
		List<String> stored = new ArrayList<>();
		try
		{
			tx.executeWithoutResult(s -> {
				try
				{
					stored.addAll(attachments.store(id, files, actor));
				}
				catch (IOException ex)
				{
					throw new UncheckedIOException(ex);
				}
			});
		}
		catch (RuntimeException ex)
		{
			attachments.deleteStoredQuietly(stored);
			throw ex;
		}
	}

	public Attachment visibleAttachment(long attachmentId, Actor actor)
	{
		Attachment a = attachmentRepo.find(attachmentId).orElseThrow(() -> new NotFound("Receipt not found."));
		visible(a.expenseId(), actor); // throws NotFound when the expense is not the user's
		return a;
	}

	public long deleteReceipt(long attachmentId, Actor actor)
	{
		Attachment a = visibleAttachment(attachmentId, actor);
		ExpenseRow e = visible(a.expenseId(), actor);
		if (!canEdit(e, actor))
		{
			throw new ExpenseRuleException("This receipt can no longer be removed.");
		}
		tx.executeWithoutResult(s -> {
			attachmentRepo.delete(attachmentId);
			audit.record(actor, "RECEIPT_DELETE", "expense", e.id(), a.originalName());
		});
		attachments.deleteFiles(List.of(a));
		return e.id();
	}

	/* ======================= validation ======================= */

	private Validated validate(ExpenseForm f, Actor actor, ExpenseRow existing)
	{
		List<String> problems = new ArrayList<>();
		LocalDate today = LocalDate.now(clock);

		LocalDate date = null;
		try
		{
			date = LocalDate.parse(f.getDate() == null ? "" : f.getDate().trim());
		}
		catch (DateTimeParseException e)
		{
			problems.add("Choose the date of the expense.");
		}
		if (date != null)
		{
			if (date.isAfter(today))
			{
				problems.add("The date cannot be in the future.");
			}
			else if (!actor.isAdmin() && date.isBefore(today.minusDays(RECEPTION_MAX_DAYS_BACK)))
			{
				problems.add("Reception can enter expenses up to " + RECEPTION_MAX_DAYS_BACK + " days back. Ask an admin for older ones.");
			}
			else if (date.isBefore(EARLIEST))
			{
				problems.add("Check the year of the date.");
			}
		}

		Optional<Option> category = options.findCategory(f.getCategory());
		if (category.isEmpty())
		{
			problems.add("Choose a category from the list.");
		}
		else if (category.get().adminOnly() && !actor.isAdmin())
		{
			problems.add("“" + category.get().name() + "” expenses are entered by an admin.");
		}

		// The restaurant manager's expenses are always the restaurant's, paid from its till.
		Optional<Option> activity = actor.isRestaurant() ? options.findActivity(RESTAURANT_ACTIVITY) : options.findActivity(f.getActivity());
		if (activity.isEmpty())
		{
			problems.add(actor.isRestaurant() ? "The “" + RESTAURANT_ACTIVITY + "” activity is switched off. Ask an admin." : "Choose what the expense is for (activity).");
		}

		// Salaries and advances name the employee; the expense is then split like their pay.
		Long employeeId = null;
		List<Split> employeeSplits = List.of();
		if (category.isPresent() && category.get().isSalaries())
		{
			if (f.getEmployeeId() == null || f.getEmployeeId().isBlank())
			{
				if (actor.isAdmin())
				{
					problems.add("Choose the employee this salary or advance is for.");
				}
			}
			else
			{
				try
				{
					EmployeeRepository.Employee e = employees.find(Long.parseLong(f.getEmployeeId().trim()))
							.orElseThrow(() -> new NumberFormatException("gone"));
					employeeId = e.id();
					if (e.splits().size() > 1)
					{
						employeeSplits = e.splits();
					}
					else if (!e.splits().isEmpty() && !actor.isRestaurant())
					{
						activity = options.findActivity(e.splits().get(0).activity()).or(() -> Optional.empty());
						if (activity.isEmpty())
						{
							problems.add("This employee's activity is no longer in the list. Update the employee first.");
						}
					}
				}
				catch (NumberFormatException e)
				{
					problems.add("Choose the employee from the list.");
				}
			}
		}

		String description = f.getDescription() == null ? "" : f.getDescription().trim().replaceAll("\\s+", " ");
		if (description.length() < 3)
		{
			problems.add("Describe the expense (what was bought or paid, to whom).");
		}
		else if (description.length() > 300)
		{
			problems.add("The description is too long (300 characters at most).");
		}

		Double amount = Money.parse(f.getAmount());
		if (!Money.isValidAmount(amount))
		{
			problems.add("Enter an amount greater than 0, for example 250 or 1 250,50.");
		}

		String payment = f.getPaymentMethod() == null ? "CASH" : f.getPaymentMethod().trim().toUpperCase(Locale.ROOT);
		if (!ExpenseModels.PAYMENT_METHODS.contains(payment))
		{
			problems.add("Choose how it was paid.");
		}

		String paidFrom = actor.isAdmin()
				? (f.getPaidFrom() == null ? "" : f.getPaidFrom().trim().toUpperCase(Locale.ROOT))
				: actor.isRestaurant() ? "RESTAURANT" : "RECEPTION";
		if (!ExpenseModels.PAID_FROM.contains(paidFrom))
		{
			problems.add("Choose where the money came from.");
		}

		// A manual split (admin) wins over the employee's; an employee split applies on its own.
		List<Split> splits = actor.isAdmin() && f.hasSplit() ? splits(f, problems) : employeeSplits;

		if (!problems.isEmpty())
		{
			throw new ExpenseRuleException(String.join("\n", problems));
		}

		// A split expense is listed under the activity with the largest share.
		String mainActivity = splits.isEmpty() ? activity.get().name() : splits.get(0).activity();
		final Long employeeRef = employeeId;

		if (existing != null)
		{
			// Keep who entered and approved it; only the content changes.
			return new Validated(new ExpenseDraft(date, category.get().name(), description, amount, payment, mainActivity,
					paidFrom, existing.status(), existing.enteredBy(), existing.approvedBy(), existing.approvedAt(),
					existing.createdByUserId(), employeeRef), splits);
		}
		boolean admin = actor.isAdmin();
		return new Validated(new ExpenseDraft(date, category.get().name(), description, amount, payment, mainActivity,
				paidFrom, admin ? "APPROVED" : "PENDING", actor.displayName(),
				admin ? actor.displayName() : null,
				admin ? Instant.now().truncatedTo(ChronoUnit.SECONDS).toString() : null,
				actor.id(), employeeRef), splits);
	}

	/**
	 * Reads the split lines: each an active activity with a percentage, no activity twice,
	 * totalling 100%. A single line at 100% is the same as no split. Sorted largest first.
	 */
	private List<Split> splits(ExpenseForm f, List<String> problems)
	{
		List<Split> splits = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		double total = 0;
		int before = problems.size();
		for (String[] row : f.getSplitRows())
		{
			String name = row[0] == null ? "" : row[0].trim();
			String pctText = row[1] == null ? "" : row[1].trim();
			if (name.isEmpty() && pctText.isEmpty())
			{
				continue;
			}
			Optional<Option> activity = options.findActivity(name);
			Double pct = percent(pctText);
			if (activity.isEmpty())
			{
				problems.add("Split: choose an activity on each line you use.");
				continue;
			}
			if (pct == null || pct <= 0 || pct > 100)
			{
				problems.add("Split: give “" + activity.get().name() + "” a percentage between 0 and 100.");
				continue;
			}
			if (!seen.add(activity.get().name().toLowerCase(Locale.ROOT)))
			{
				problems.add("Split: “" + activity.get().name() + "” appears twice.");
				continue;
			}
			splits.add(new Split(activity.get().name(), pct));
			total += pct;
		}
		if (problems.size() > before)
		{
			return List.of();
		}
		if (splits.size() == 1 && Math.abs(splits.get(0).percent() - 100) < 0.01)
		{
			return List.of(); // "100% Terrain" is simply Terrain
		}
		if (splits.size() == 1)
		{
			problems.add("Split: use at least two activities, or leave the split empty.");
			return List.of();
		}
		if (Math.abs(total - 100) > 0.01)
		{
			problems.add("Split: the percentages add up to " + String.format(Locale.ROOT, "%.2f", total).replaceAll("\\.?0+$", "")
					+ "%, they must add up to 100%.");
			return List.of();
		}
		splits.sort(java.util.Comparator.comparingDouble(Split::percent).reversed());
		return splits;
	}

	private static Double percent(String text)
	{
		if (text == null || text.isBlank())
		{
			return null;
		}
		try
		{
			double v = Double.parseDouble(text.replace("%", "").replace(",", ".").trim());
			return Math.round(v * 100) / 100.0;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	static String describe(List<Split> splits)
	{
		if (splits == null || splits.isEmpty())
		{
			return "";
		}
		StringBuilder sb = new StringBuilder(" split:");
		for (Split s : splits)
		{
			sb.append(' ').append(s.activity()).append(' ').append(s.percent()).append('%');
		}
		return sb.toString();
	}

	private String similarNotice(ExpenseDraft d, long id)
	{
		return expenses.similar(d.date(), d.category(), d.amount(), id)
				.map(other -> "Note: expense #" + other + " has the same date, category and amount. Check it is not entered twice.")
				.orElse(null);
	}

	private static void requireAdmin(Actor actor)
	{
		if (!actor.isAdmin())
		{
			throw new ExpenseRuleException("Only an admin can do this.");
		}
	}

	static String describe(ExpenseDraft d)
	{
		return d.date() + " " + d.category() + " " + money(d.amount()) + " from " + d.paidFrom() + " [" + d.status() + "] " + d.description();
	}

	static String describe(ExpenseRow e)
	{
		return e.date() + " " + e.category() + " " + money(e.amount()) + " from " + e.paidFrom() + " [" + e.status() + "] " + e.description();
	}

	private static String money(double v)
	{
		return String.format(Locale.US, "%,.2f", v);
	}

	private static String trimTo(String s, int max)
	{
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}
}
