package com.cslsm.web.admin;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Read-only access to the append-only audit_log (V10) for the viewer. */
@Repository
@DependsOn("flyway")
public class AuditLogRepository
{
	private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm:ss");

	public record Entry(long id, String at, String username, String action, String entity, String entityId, String details,
						String clientIp)
	{
		/** The instant in the server's time zone, for people. */
		public String atLocal()
		{
			try
			{
				return LOCAL.format(Instant.parse(at).atZone(ZoneId.systemDefault()));
			}
			catch (DateTimeParseException e)
			{
				return at;
			}
		}

		/** Sign-in failures and lockouts stand out in the list. */
		public boolean isAlert()
		{
			return action != null && (action.startsWith("LOGIN_") || action.equals("ACCOUNT_LOCKED"));
		}
	}

	/** Every filter is optional; text searches the details, entity and id. */
	public record Filter(LocalDate from, LocalDate to, String username, String action, String text)
	{
	}

	private static final RowMapper<Entry> MAPPER = (rs, n) -> new Entry(rs.getLong("id"), rs.getString("at"),
			rs.getString("username"), rs.getString("action"), rs.getString("entity"), rs.getString("entity_id"),
			rs.getString("details"), rs.getString("client_ip"));

	private final JdbcTemplate jdbc;

	public AuditLogRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	public List<Entry> search(Filter f, int limit, int offset)
	{
		List<Object> args = new ArrayList<>();
		String where = where(f, args);
		args.add(limit);
		args.add(offset);
		return jdbc.query("SELECT * FROM audit_log" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", MAPPER, args.toArray());
	}

	public int count(Filter f)
	{
		List<Object> args = new ArrayList<>();
		String where = where(f, args);
		Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log" + where, Integer.class, args.toArray());
		return n == null ? 0 : n;
	}

	public List<String> usernames()
	{
		return jdbc.queryForList("SELECT DISTINCT username FROM audit_log ORDER BY username", String.class);
	}

	public List<String> actions()
	{
		return jdbc.queryForList("SELECT DISTINCT action FROM audit_log ORDER BY action", String.class);
	}

	private static String where(Filter f, List<Object> args)
	{
		StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
		ZoneId zone = ZoneId.systemDefault();
		if (f.from() != null)
		{
			sql.append(" AND at >= ?");
			args.add(f.from().atStartOfDay(zone).toInstant().toString());
		}
		if (f.to() != null)
		{
			sql.append(" AND at < ?");
			args.add(f.to().plusDays(1).atStartOfDay(zone).toInstant().toString());
		}
		if (f.username() != null && !f.username().isBlank())
		{
			sql.append(" AND username = ?");
			args.add(f.username().trim());
		}
		if (f.action() != null && !f.action().isBlank())
		{
			sql.append(" AND action = ?");
			args.add(f.action().trim());
		}
		if (f.text() != null && !f.text().isBlank())
		{
			String like = "%" + f.text().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
			sql.append(" AND (details LIKE ? ESCAPE '\\' OR entity LIKE ? ESCAPE '\\' OR entity_id LIKE ? ESCAPE '\\')");
			args.add(like);
			args.add(like);
			args.add(like);
		}
		return sql.toString();
	}
}
