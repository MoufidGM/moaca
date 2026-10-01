package com.cslsm.web.admin;

import com.cslsm.web.admin.CategoryRepository.Category;
import com.cslsm.web.finance.FinanceRepository;
import com.cslsm.web.security.AppUser;
import com.cslsm.web.security.PasswordController;
import com.cslsm.web.security.Role;
import com.cslsm.web.security.UserRepository;
import com.cslsm.web.support.Actor;
import com.cslsm.web.support.AuditService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the super admin manages from the browser: accounts, expense categories and the
 * year-mode setting. Every change is written to the audit log in the same transaction.
 *
 * Accounts are never deleted — their name stays on the entries they made — only switched
 * off. The app always keeps at least one super admin who can sign in.
 */
@Service
public class AdminService
{
	public static class AdminException extends RuntimeException
	{
		public AdminException(String message)
		{
			super(message);
		}
	}

	public static final List<String[]> HEADINGS = List.of(
			new String[]{"SALARIES", "Salaries"},
			new String[]{"MAINTENANCE", "Maintenance and services"},
			new String[]{"PURCHASES", "Purchases and equipment"},
			new String[]{"UTILITIES", "Electricity, water, phone, internet"},
			new String[]{"OTHER", "Other"});

	/** Payroll and salary advances write these names directly, so they stay as they are. */
	private static final Set<String> SYSTEM_CATEGORIES = Set.of("salaries", "salary advance");

	private static final int MAX_NAME = 60;

	private final UserRepository users;
	private final CategoryRepository categories;
	private final FinanceRepository finance;
	private final PasswordEncoder passwordEncoder;
	private final AuditService audit;
	private final TransactionTemplate tx;

	public AdminService(UserRepository users, CategoryRepository categories, FinanceRepository finance,
						PasswordEncoder passwordEncoder, AuditService audit, TransactionTemplate tx)
	{
		this.users = users;
		this.categories = categories;
		this.finance = finance;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
		this.tx = tx;
	}

	/* ======================= accounts ======================= */

	public record UserInput(String username, String displayName, String role, boolean enabled, String password, String repeat)
	{
	}

	public long createUser(UserInput in, Actor actor)
	{
		requireSuperAdmin(actor);
		String username = in.username() == null ? "" : in.username().trim();
		if (!username.matches("[A-Za-z0-9._-]{3,32}"))
		{
			throw new AdminException("The username must be 3 to 32 characters: letters, digits, dot, dash or underscore.");
		}
		if (users.findByUsername(username).isPresent())
		{
			throw new AdminException("There is already an account named “" + username + "”.");
		}
		Role role = checkedRole(in.role());
		String name = checkedDisplayName(in.displayName(), username);
		String password = checkedPassword(in.password(), in.repeat());
		return tx.execute(s -> {
			long id = users.create(username, name, passwordEncoder.encode(password), role, true);
			audit.record(actor, "USER_CREATE", "app_user", id, username + " (" + role.label() + ")");
			return id;
		});
	}

	public void updateUser(long id, String displayName, String roleText, boolean enabled, Actor actor)
	{
		requireSuperAdmin(actor);
		AppUser user = existing(id);
		Role role = checkedRole(roleText);
		String name = checkedDisplayName(displayName, user.username());
		boolean self = user.id() == actor.id();
		if (self && (role != user.role() || !enabled))
		{
			throw new AdminException("You cannot change your own role or switch off your own account. Ask another super admin.");
		}
		boolean losesSuperAdmin = user.role() == Role.SUPER_ADMIN && user.enabled() && (role != Role.SUPER_ADMIN || !enabled);
		if (losesSuperAdmin && users.countEnabledSuperAdmins() <= 1)
		{
			throw new AdminException("This is the only super admin who can sign in. Create another one first.");
		}
		tx.executeWithoutResult(s -> {
			users.updateProfile(id, name, role, enabled);
			StringBuilder details = new StringBuilder(user.username());
			if (!name.equals(user.displayName()))
			{
				details.append(" name: ").append(user.displayName()).append(" -> ").append(name);
			}
			if (role != user.role())
			{
				details.append(" role: ").append(user.role().label()).append(" -> ").append(role.label());
			}
			if (enabled != user.enabled())
			{
				details.append(enabled ? " switched on" : " switched off");
			}
			audit.record(actor, "USER_UPDATE", "app_user", id, details.toString());
		});
	}

	/** A temporary password: the user must replace it at the next sign-in. */
	public void resetPassword(long id, String password, String repeat, Actor actor)
	{
		requireSuperAdmin(actor);
		AppUser user = existing(id);
		if (user.id() == actor.id())
		{
			throw new AdminException("Change your own password from the Password page.");
		}
		String checked = checkedPassword(password, repeat);
		tx.executeWithoutResult(s -> {
			users.updatePassword(id, passwordEncoder.encode(checked), true);
			audit.record(actor, "USER_PASSWORD_RESET", "app_user", id, user.username() + " (temporary password, account unlocked)");
		});
	}

	/** Lost phone: the user scans a new QR code at the next sign-in. */
	public void resetTwoFactor(long id, Actor actor)
	{
		requireSuperAdmin(actor);
		AppUser user = existing(id);
		tx.executeWithoutResult(s -> {
			users.resetTotp(id);
			audit.record(actor, "USER_2FA_RESET", "app_user", id, user.username());
		});
	}

	public void unlock(long id, Actor actor)
	{
		requireSuperAdmin(actor);
		AppUser user = existing(id);
		tx.executeWithoutResult(s -> {
			users.unlock(id);
			audit.record(actor, "USER_UNLOCK", "app_user", id, user.username());
		});
	}

	private AppUser existing(long id)
	{
		return users.findById(id).orElseThrow(() -> new AdminException("Account not found."));
	}

	private static Role checkedRole(String text)
	{
		try
		{
			return Role.valueOf(text == null ? "" : text.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e)
		{
			throw new AdminException("Choose a role.");
		}
	}

	private static String checkedDisplayName(String text, String fallback)
	{
		String name = text == null ? "" : text.trim();
		if (name.isEmpty())
		{
			return fallback;
		}
		if (name.length() > MAX_NAME)
		{
			throw new AdminException("The name is too long (max " + MAX_NAME + " characters).");
		}
		return name;
	}

	private static String checkedPassword(String password, String repeat)
	{
		String p = password == null ? "" : password;
		if (p.length() < PasswordController.MIN_PASSWORD_LENGTH)
		{
			throw new AdminException("The password must be at least " + PasswordController.MIN_PASSWORD_LENGTH + " characters.");
		}
		if (p.length() > PasswordController.MAX_PASSWORD_LENGTH)
		{
			throw new AdminException("The password is too long.");
		}
		if (!p.equals(repeat))
		{
			throw new AdminException("The two passwords do not match.");
		}
		return p;
	}

	/* ======================= expense categories ======================= */

	public void createCategory(String nameText, String heading, boolean adminOnly, String sortOrderText, Actor actor)
	{
		requireSuperAdmin(actor);
		String name = nameText == null ? "" : nameText.trim();
		if (name.isEmpty() || name.length() > MAX_NAME)
		{
			throw new AdminException("Give the category a name of up to " + MAX_NAME + " characters.");
		}
		if (categories.findByName(name).isPresent())
		{
			throw new AdminException("There is already a category named “" + name + "”.");
		}
		String h = checkedHeading(heading);
		int order = checkedOrder(sortOrderText);
		tx.executeWithoutResult(s -> {
			categories.insert(name, h, adminOnly, order);
			audit.record(actor, "CATEGORY_CREATE", "expense_option", null, name + " (" + h + (adminOnly ? ", admins only" : "") + ")");
		});
	}

	public void updateCategory(long id, String heading, boolean adminOnly, boolean active, String sortOrderText, Actor actor)
	{
		requireSuperAdmin(actor);
		Category c = categories.find(id).orElseThrow(() -> new AdminException("Category not found."));
		String h = checkedHeading(heading);
		int order = checkedOrder(sortOrderText);
		if (SYSTEM_CATEGORIES.contains(c.name().toLowerCase(Locale.ROOT)) && (!active || !"SALARIES".equals(h)))
		{
			throw new AdminException("“" + c.name() + "” is used by payroll and salary advances: it stays in use under Salaries.");
		}
		tx.executeWithoutResult(s -> {
			categories.update(id, h, adminOnly, active, order);
			audit.record(actor, "CATEGORY_UPDATE", "expense_option", id, c.name() + ": " + c.heading() + (c.adminOnly() ? " admins" : "")
					+ (c.active() ? "" : " off") + " #" + c.sortOrder() + " -> " + h + (adminOnly ? " admins" : "") + (active ? "" : " off") + " #" + order);
		});
	}

	private static String checkedHeading(String heading)
	{
		String h = heading == null ? "" : heading.trim().toUpperCase(Locale.ROOT);
		if (HEADINGS.stream().noneMatch(x -> x[0].equals(h)))
		{
			throw new AdminException("Choose where the category counts in the activity analysis.");
		}
		return h;
	}

	private static int checkedOrder(String text)
	{
		try
		{
			int n = text == null || text.isBlank() ? 100 : Integer.parseInt(text.trim());
			if (n < 0 || n > 9999)
			{
				throw new NumberFormatException();
			}
			return n;
		}
		catch (NumberFormatException e)
		{
			throw new AdminException("The position must be a number between 0 and 9999.");
		}
	}

	/** A one-line audited change made by the controller (frequent expenses on/off). */
	public void audit(Actor actor, String action, String entity, Object id, String details)
	{
		requireSuperAdmin(actor);
		this.audit.record(actor, action, entity, id, details);
	}

	/* ======================= settings ======================= */

	public void setResetEachYear(boolean reset, Actor actor)
	{
		requireSuperAdmin(actor);
		boolean before = finance.resetEachYear();
		tx.executeWithoutResult(s -> {
			finance.setResetEachYear(reset);
			audit.record(actor, "SETTING_CHANGE", "app_setting", "storage.year_mode",
					(before ? "RESET" : "CARRY") + " -> " + (reset ? "RESET" : "CARRY"));
		});
	}

	private static void requireSuperAdmin(Actor actor)
	{
		if (actor.role() != Role.SUPER_ADMIN)
		{
			throw new AdminException("Only the super admin can do this.");
		}
	}
}
