package com.cslsm.web.security;

public final class TwoFactor
{
	/**
	 * Granted after a correct password when a second factor is still required.
	 * Deliberately not a ROLE_ so no role-based rule ever matches it.
	 */
	public static final String PENDING_AUTHORITY = "TWO_FACTOR_PENDING";

	/** Time allowed to type the code after the password was accepted. */
	public static final int PENDING_SECONDS = 5 * 60;

	private TwoFactor()
	{
	}
}
