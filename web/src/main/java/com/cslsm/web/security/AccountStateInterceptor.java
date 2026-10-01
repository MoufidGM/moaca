package com.cslsm.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Optional;

/**
 * Makes changes made on the Administration page take effect on open sessions:
 *
 *   - an account that was switched off, or whose role changed, is signed out at its next
 *     request (the role in the session no longer matches the database);
 *   - an account with a temporary password can only open the change-password page until it
 *     has chosen its own.
 *
 * Runs after Spring Security, so the URL rules have already been applied.
 */
@Component
public class AccountStateInterceptor implements HandlerInterceptor, WebMvcConfigurer
{
	public static final String CHANGE_PASSWORD_PATH = "/account/password";

	private final UserRepository users;

	public AccountStateInterceptor(UserRepository users)
	{
		this.users = users;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry)
	{
		registry.addInterceptor(this)
				.excludePathPatterns("/login", "/logout", "/error", "/css/**", "/js/**", "/favicon.ico", "/2fa/**");
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception
	{
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated() || auth.getAuthorities().stream()
				.noneMatch(a -> a.getAuthority().startsWith("ROLE_")))
		{
			return true; // anonymous, or the password step only: nothing to check yet
		}
		Optional<AppUser> found = users.findByUsername(auth.getName());
		if (found.isEmpty())
		{
			return true; // test doubles and the like: the controllers decide
		}
		AppUser user = found.get();
		boolean roleMatches = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(user.role().authority()));
		if (!user.enabled() || !roleMatches)
		{
			SecurityContextHolder.clearContext();
			HttpSession session = request.getSession(false);
			if (session != null)
			{
				session.invalidate();
			}
			response.sendRedirect(request.getContextPath() + "/login?expired");
			return false;
		}
		String path = request.getRequestURI().substring(request.getContextPath().length());
		if (user.passwordChangeRequired() && !path.startsWith(CHANGE_PASSWORD_PATH))
		{
			response.sendRedirect(request.getContextPath() + CHANGE_PASSWORD_PATH);
			return false;
		}
		return true;
	}
}
