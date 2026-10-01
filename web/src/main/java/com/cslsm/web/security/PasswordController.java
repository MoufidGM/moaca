package com.cslsm.web.security;

import com.cslsm.web.support.AuditService;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Every signed-in user can change their own password. Also where an account with a
 * temporary password (set by the super admin) lands until it has chosen its own — see
 * AccountStateInterceptor.
 */
@Controller
@RequestMapping(AccountStateInterceptor.CHANGE_PASSWORD_PATH)
public class PasswordController
{
	public static final int MIN_PASSWORD_LENGTH = 12;
	public static final int MAX_PASSWORD_LENGTH = 200;

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final AuditService audit;

	public PasswordController(UserRepository users, PasswordEncoder passwordEncoder, AuditService audit)
	{
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
	}

	@GetMapping
	public String form(Authentication auth, Model model)
	{
		model.addAttribute("mustChange", current(auth).passwordChangeRequired());
		return "password";
	}

	@PostMapping
	public String change(@RequestParam(defaultValue = "") String current, @RequestParam(defaultValue = "") String password,
						 @RequestParam(defaultValue = "") String repeat, Authentication auth, Model model,
						 RedirectAttributes redirect)
	{
		AppUser user = current(auth);
		String problem = problem(user, current, password, repeat);
		if (problem != null)
		{
			model.addAttribute("error", problem);
			model.addAttribute("mustChange", user.passwordChangeRequired());
			return "password";
		}
		users.updatePassword(user.id(), passwordEncoder.encode(password), false);
		audit.record(user.username(), "PASSWORD_CHANGE", "app_user", user.id(),
				user.passwordChangeRequired() ? "temporary password replaced" : null);
		redirect.addFlashAttribute("flashOk", "Password changed.");
		return "redirect:/";
	}

	/** The reason the change is refused, or null. Same password rules as the server terminal. */
	static String problem(AppUser user, String current, String password, String repeat, PasswordEncoder encoder)
	{
		if (!encoder.matches(current, user.passwordHash()))
		{
			return "The current password is wrong.";
		}
		if (password.length() < MIN_PASSWORD_LENGTH)
		{
			return "The new password must be at least " + MIN_PASSWORD_LENGTH + " characters.";
		}
		if (password.length() > MAX_PASSWORD_LENGTH)
		{
			return "The new password is too long.";
		}
		if (!password.equals(repeat))
		{
			return "The two new passwords do not match.";
		}
		if (password.equals(current))
		{
			return "Choose a password different from the current one.";
		}
		return null;
	}

	private String problem(AppUser user, String current, String password, String repeat)
	{
		return problem(user, current, password, repeat, passwordEncoder);
	}

	private AppUser current(Authentication auth)
	{
		return users.findByUsername(auth.getName()).filter(AppUser::enabled)
				.orElseThrow(() -> new IllegalStateException("Signed-in user not found or disabled"));
	}
}
