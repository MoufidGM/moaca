package com.cslsm.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * A user who passed the password step but not the code step is sent to the code page
 * instead of seeing "forbidden". Everyone else gets a plain 403.
 */
public class TwoFactorAccessDeniedHandler implements AccessDeniedHandler
{
	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied) throws IOException
	{
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		boolean pending = auth != null && auth.getAuthorities().stream()
				.anyMatch(a -> TwoFactor.PENDING_AUTHORITY.equals(a.getAuthority()));
		if (pending)
		{
			response.sendRedirect(request.getContextPath() + "/2fa");
			return;
		}
		response.sendError(HttpServletResponse.SC_FORBIDDEN);
	}
}
