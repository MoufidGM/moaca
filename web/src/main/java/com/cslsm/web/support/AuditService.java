package com.cslsm.web.support;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Writes to the append-only audit_log (see V10). Called inside the same transaction as the
 * change it describes, so a change is never saved without its audit entry.
 */
@Service
@DependsOn("flyway")
public class AuditService
{
	private static final int MAX_DETAILS = 2000;

	private final JdbcTemplate jdbc;

	public AuditService(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	public void record(String username, String action, String entity, Object entityId, String details)
	{
		String trimmed = details == null ? null
				: details.length() > MAX_DETAILS ? details.substring(0, MAX_DETAILS) + "…" : details;
		jdbc.update("""
						INSERT INTO audit_log (at, username, action, entity, entity_id, details, client_ip)
						VALUES (?, ?, ?, ?, ?, ?, ?)
						""",
				Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(), username, action, entity,
				entityId == null ? null : String.valueOf(entityId), trimmed, clientIp());
	}

	public void record(Actor actor, String action, String entity, Object entityId, String details)
	{
		record(actor.username(), action, entity, entityId, details);
	}

	/** Client address as seen through nginx (forward-headers-strategy: native). */
	private static String clientIp()
	{
		RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
		if (attrs instanceof ServletRequestAttributes servlet)
		{
			HttpServletRequest request = servlet.getRequest();
			return request.getRemoteAddr();
		}
		return null;
	}
}
