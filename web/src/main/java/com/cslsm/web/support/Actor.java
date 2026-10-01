package com.cslsm.web.support;

import com.cslsm.web.security.Role;

/** The signed-in person performing an action. */
public record Actor(long id, String username, String displayName, Role role)
{
	public boolean isAdmin()
	{
		return role.isAdmin();
	}

	public boolean isRestaurant()
	{
		return role == Role.RESTAURANT_MANAGER;
	}

	public boolean isReception()
	{
		return role == Role.RECEPTIONIST;
	}
}
