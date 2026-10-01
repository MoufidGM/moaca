package com.cslsm.web.security;

import com.cslsm.web.support.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Runs after a correct password.
 *
 * Users who need a second factor are downgraded to a "pending" login that can only open the
 * code page; their real role is granted by TwoFactorController once the code is verified.
 * Everyone else is fully signed in here.
 */
@Component
public class LoginSuccessHandler implements AuthenticationSuccessHandler
{
	private final UserRepository users;
	private final SecurityContextRepository contextRepository;
	private final CslsmSecurityProperties properties;
	private final AuditService audit;

	public LoginSuccessHandler(UserRepository users,
							   SecurityContextRepository contextRepository,
							   CslsmSecurityProperties properties,
							   AuditService audit)
	{
		this.users = users;
		this.contextRepository = contextRepository;
		this.properties = properties;
		this.audit = audit;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
										Authentication authentication) throws IOException
	{
		AppUser user = users.findByUsername(authentication.getName())
				.orElseThrow(() -> new IllegalStateException("Authenticated user vanished"));

		if (user.needsSecondFactor())
		{
			Authentication pending = UsernamePasswordAuthenticationToken.authenticated(
					authentication.getPrincipal(), null,
					List.of(new SimpleGrantedAuthority(TwoFactor.PENDING_AUTHORITY)));
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(pending);
			SecurityContextHolder.setContext(context);
			contextRepository.saveContext(context, request, response);
			request.getSession().setMaxInactiveInterval(TwoFactor.PENDING_SECONDS);

			response.sendRedirect(request.getContextPath() + (user.totpEnabled() ? "/2fa" : "/2fa/setup"));
			return;
		}

		users.recordSuccessfulLogin(user.username());
		audit.record(user.username(), "LOGIN", "app_user", user.id(), "password");
		request.getSession().setMaxInactiveInterval(properties.idleSeconds(user.role()));
		response.sendRedirect(request.getContextPath() + "/");
	}
}
