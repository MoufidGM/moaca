package com.cslsm.web.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "cslsm.security")
public record CslsmSecurityProperties(
		String secretKey,
		@DefaultValue("CSLSM") String totpIssuer,
		@DefaultValue("15") int receptionistIdleMinutes,
		@DefaultValue("60") int adminIdleMinutes,
		@DefaultValue("5") int maxFailedLogins,
		@DefaultValue("15") int lockoutMinutes)
{
	/** Session idle timeout in seconds for a role (receptionists share desk PCs). */
	public int idleSeconds(Role role)
	{
		return (role == Role.RECEPTIONIST ? receptionistIdleMinutes : adminIdleMinutes) * 60;
	}

	public Duration lockout()
	{
		return Duration.ofMinutes(lockoutMinutes);
	}
}
