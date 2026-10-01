package com.cslsm.web.support;

import com.cslsm.web.security.AppUser;
import com.cslsm.web.security.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the signed-in user from the database on every call, so a disabled account or a
 * changed role takes effect immediately rather than at the next login.
 */
@Component
public class CurrentActor
{
	private final UserRepository users;

	public CurrentActor(UserRepository users)
	{
		this.users = users;
	}

	public Actor require()
	{
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated())
		{
			throw new AccessDeniedException("Not signed in");
		}
		AppUser user = users.findByUsername(auth.getName())
				.filter(AppUser::enabled)
				.orElseThrow(() -> new AccessDeniedException("Account not found or disabled"));
		return new Actor(user.id(), user.username(), user.displayName(), user.role());
	}
}
