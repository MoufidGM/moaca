package com.cslsm.web.security;

import com.cslsm.web.support.AuditService;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationFailureLockedEvent;
import org.springframework.stereotype.Component;

/**
 * Counts wrong passwords toward the lockout and writes them to the audit log. Counters are
 * reset only after a complete login (password AND second factor), so knowing a password
 * never buys unlimited code guesses.
 */
@Component
public class LoginAttemptListener
{
	private final UserRepository users;
	private final CslsmSecurityProperties properties;
	private final AuditService audit;

	public LoginAttemptListener(UserRepository users, CslsmSecurityProperties properties, AuditService audit)
	{
		this.users = users;
		this.properties = properties;
		this.audit = audit;
	}

	@EventListener
	public void onBadCredentials(AuthenticationFailureBadCredentialsEvent event)
	{
		String username = name(event.getAuthentication().getPrincipal());
		if (username == null)
		{
			return;
		}
		users.recordFailedLogin(username, properties.maxFailedLogins(), properties.lockout());
		boolean nowLocked = users.findByUsername(username).map(u -> u.isLocked(System.currentTimeMillis())).orElse(false);
		audit.record(username, nowLocked ? "ACCOUNT_LOCKED" : "LOGIN_FAILED", "app_user", null, "wrong password");
	}

	@EventListener
	public void onLocked(AuthenticationFailureLockedEvent event)
	{
		String username = name(event.getAuthentication().getPrincipal());
		if (username != null)
		{
			audit.record(username, "LOGIN_REFUSED_LOCKED", "app_user", null, null);
		}
	}

	/** Usernames typed by strangers go into the log, so keep them short. */
	private static String name(Object principal)
	{
		if (principal == null)
		{
			return null;
		}
		String s = principal.toString().trim();
		return s.length() > 64 ? s.substring(0, 64) : s;
	}
}
