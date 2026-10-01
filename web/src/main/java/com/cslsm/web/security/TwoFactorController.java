package com.cslsm.web.security;

import com.cslsm.web.support.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.List;

/**
 * Second login step. Only reachable with the TWO_FACTOR_PENDING authority (see SecurityConfig).
 *
 *   GET/POST /2fa         enter the 6-digit code
 *   GET/POST /2fa/setup   first login: scan the QR code, confirm with a code
 */
@Controller
@RequestMapping("/2fa")
public class TwoFactorController
{
	private final UserRepository users;
	private final TotpService totp;
	private final SecretCipher cipher;
	private final SecurityContextRepository contextRepository;
	private final CslsmSecurityProperties properties;
	private final AuditService audit;

	public TwoFactorController(UserRepository users, TotpService totp, SecretCipher cipher,
							   SecurityContextRepository contextRepository, CslsmSecurityProperties properties,
							   AuditService audit)
	{
		this.users = users;
		this.totp = totp;
		this.cipher = cipher;
		this.contextRepository = contextRepository;
		this.properties = properties;
		this.audit = audit;
	}

	@GetMapping
	public String codeForm(Authentication auth)
	{
		AppUser user = currentUser(auth);
		return user.totpEnabled() ? "2fa" : "redirect:/2fa/setup";
	}

	@PostMapping
	public String verifyCode(@RequestParam(defaultValue = "") String code, Authentication auth,
							 HttpServletRequest request, HttpServletResponse response)
	{
		AppUser user = currentUser(auth);
		if (!user.totpEnabled())
		{
			return "redirect:/2fa/setup";
		}
		long step = totp.verify(cipher.decrypt(user.totpSecretEncrypted()), code, Instant.now());
		if (step >= 0 && users.acceptTotpStep(user.id(), step))
		{
			completeLogin(user, auth, request, response);
			return "redirect:/";
		}
		return failedCode(user, "/2fa", request);
	}

	@GetMapping("/setup")
	public String setupForm(Authentication auth, Model model)
	{
		AppUser user = currentUser(auth);
		if (user.totpEnabled())
		{
			return "redirect:/2fa";
		}

		// Reuse the pending secret so reloading the page does not invalidate a scanned code.
		String secret;
		if (user.totpSecretEncrypted() == null)
		{
			secret = totp.newSecret();
			users.savePendingTotpSecret(user.id(), cipher.encrypt(secret));
		}
		else
		{
			secret = cipher.decrypt(user.totpSecretEncrypted());
		}

		String uri = totp.otpauthUri(properties.totpIssuer(), user.username(), secret);
		model.addAttribute("qrSvg", QrSvg.render(uri, 5));
		model.addAttribute("secretGrouped", secret.replaceAll("(.{4})(?!$)", "$1 "));
		return "2fa-setup";
	}

	@PostMapping("/setup")
	public String confirmSetup(@RequestParam(defaultValue = "") String code, Authentication auth,
							   HttpServletRequest request, HttpServletResponse response)
	{
		AppUser user = currentUser(auth);
		if (user.totpEnabled() || user.totpSecretEncrypted() == null)
		{
			return "redirect:/2fa/setup";
		}
		long step = totp.verify(cipher.decrypt(user.totpSecretEncrypted()), code, Instant.now());
		if (step >= 0)
		{
			users.enableTotp(user.id(), step);
			completeLogin(user, auth, request, response);
			return "redirect:/";
		}
		return failedCode(user, "/2fa/setup", request);
	}

	/* ---------------------------------------------------------------- */

	private AppUser currentUser(Authentication auth)
	{
		return users.findByUsername(auth.getName())
				.filter(AppUser::enabled)
				.orElseThrow(() -> new IllegalStateException("Signed-in user not found or disabled"));
	}

	/** Replaces the pending login with the user's real role, in a fresh session id. */
	private void completeLogin(AppUser user, Authentication pending,
							   HttpServletRequest request, HttpServletResponse response)
	{
		Authentication full = UsernamePasswordAuthenticationToken.authenticated(
				pending.getPrincipal(), null, List.of(new SimpleGrantedAuthority(user.role().authority())));
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(full);
		SecurityContextHolder.setContext(context);

		request.changeSessionId();
		contextRepository.saveContext(context, request, response);
		request.getSession().setMaxInactiveInterval(properties.idleSeconds(user.role()));
		users.recordSuccessfulLogin(user.username());
		audit.record(user.username(), "LOGIN", "app_user", user.id(), "password + authenticator code");
	}

	/** Wrong codes count toward the same lockout as wrong passwords. */
	private String failedCode(AppUser user, String retryPath, HttpServletRequest request)
	{
		users.recordFailedLogin(user.username(), properties.maxFailedLogins(), properties.lockout());
		boolean locked = users.findById(user.id())
				.map(u -> u.isLocked(System.currentTimeMillis()))
				.orElse(true);
		audit.record(user.username(), locked ? "ACCOUNT_LOCKED" : "LOGIN_FAILED", "app_user", user.id(), "wrong authenticator code");
		if (locked)
		{
			SecurityContextHolder.clearContext();
			HttpSession session = request.getSession(false);
			if (session != null)
			{
				session.invalidate();
			}
			return "redirect:/login?locked";
		}
		return "redirect:" + retryPath + "?error";
	}
}
