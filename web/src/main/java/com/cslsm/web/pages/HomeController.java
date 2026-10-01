package com.cslsm.web.pages;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Set;

@Controller
public class HomeController
{
	private static final Set<String> ADMIN_AUTHORITIES = Set.of("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
	private static final Set<String> RESTAURANT = Set.of("ROLE_RESTAURANT_MANAGER");
	private static final Set<String> ALL_ROLES = Set.of("ROLE_RECEPTIONIST", "ROLE_RESTAURANT_MANAGER", "ROLE_ADMIN", "ROLE_SUPER_ADMIN");

	/** Admins land on the dashboard, the restaurant on its page, the reception on Daily logs. */
	@GetMapping("/")
	public String home(Authentication auth)
	{
		if (has(auth, ADMIN_AUTHORITIES))
		{
			return "redirect:/dashboard";
		}
		return has(auth, RESTAURANT) ? "redirect:/restaurant" : "redirect:/daily-logs";
	}

	@GetMapping("/login")
	public String login(Authentication auth)
	{
		return has(auth, ALL_ROLES) ? "redirect:/" : "login";
	}

	static boolean has(Authentication auth, Set<String> authorities)
	{
		return auth != null && auth.getAuthorities().stream().anyMatch(a -> authorities.contains(a.getAuthority()));
	}
}
