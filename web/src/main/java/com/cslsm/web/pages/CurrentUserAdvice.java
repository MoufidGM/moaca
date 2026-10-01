package com.cslsm.web.pages;

import com.cslsm.web.security.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Adds the signed-in user's name and role to every page (top bar). */
@ControllerAdvice
public class CurrentUserAdvice
{
	public record CurrentUser(String displayName, String roleLabel)
	{
	}

	private final UserRepository users;

	public CurrentUserAdvice(UserRepository users)
	{
		this.users = users;
	}

	@ModelAttribute("currentUser")
	public CurrentUser currentUser(Authentication auth)
	{
		if (auth == null)
		{
			return null;
		}
		return users.findByUsername(auth.getName())
				.map(u -> new CurrentUser(u.displayName(), u.role().label()))
				.orElse(null);
	}
}
