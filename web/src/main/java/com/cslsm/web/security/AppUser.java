package com.cslsm.web.security;

public record AppUser(
		long id,
		String username,
		String displayName,
		String passwordHash,
		Role role,
		boolean enabled,
		String totpSecretEncrypted,
		boolean totpEnabled,
		Long totpLastStep,
		int failedAttempts,
		Long lockedUntilMillis,
		String allowedDevices,
		boolean passwordChangeRequired,
		String createdAt,
		String lastLoginAt)
{
	public boolean isLocked(long nowMillis)
	{
		return lockedUntilMillis != null && lockedUntilMillis > nowMillis;
	}

	/** Two-factor is required for admins, and for anyone who chose to enroll. */
	public boolean needsSecondFactor()
	{
		return role.requiresTwoFactor() || totpEnabled;
	}
}
