package com.cslsm.web.security;

public enum Role
{
	RECEPTIONIST("Receptionist"),
	RESTAURANT_MANAGER("Restaurant manager"),
	ADMIN("Admin"),
	SUPER_ADMIN("Super admin");

	private final String label;

	Role(String label)
	{
		this.label = label;
	}

	public String label()
	{
		return label;
	}

	/** Spring Security authority name, e.g. ROLE_ADMIN. */
	public String authority()
	{
		return "ROLE_" + name();
	}

	/** Two-factor is mandatory for anyone who can see money totals. */
	public boolean requiresTwoFactor()
	{
		return isAdmin();
	}

	public boolean isAdmin()
	{
		return this == ADMIN || this == SUPER_ADMIN;
	}

	/** Enters data for one place only (the reception or the restaurant). */
	public boolean isStaff()
	{
		return this == RECEPTIONIST || this == RESTAURANT_MANAGER;
	}
}
