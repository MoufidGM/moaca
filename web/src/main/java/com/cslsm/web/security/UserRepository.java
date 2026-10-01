package com.cslsm.web.security;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Repository
@DependsOn("flyway")
public class UserRepository
{
	private static final RowMapper<AppUser> MAPPER = UserRepository::map;

	private final JdbcTemplate jdbc;

	public UserRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	private static AppUser map(ResultSet rs, int rowNum) throws SQLException
	{
		return new AppUser(
				rs.getLong("id"),
				rs.getString("username"),
				rs.getString("display_name"),
				rs.getString("password_hash"),
				Role.valueOf(rs.getString("role")),
				rs.getInt("enabled") != 0,
				rs.getString("totp_secret"),
				rs.getInt("totp_enabled") != 0,
				nullableLong(rs, "totp_last_step"),
				rs.getInt("failed_attempts"),
				nullableLong(rs, "locked_until"),
				rs.getString("allowed_devices"),
				rs.getInt("password_change_required") != 0,
				rs.getString("created_at"),
				rs.getString("last_login_at"));
	}

	private static Long nullableLong(ResultSet rs, String column) throws SQLException
	{
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	public Optional<AppUser> findByUsername(String username)
	{
		List<AppUser> rows = jdbc.query("SELECT * FROM app_user WHERE username = ?", MAPPER, username);
		return rows.stream().findFirst();
	}

	public Optional<AppUser> findById(long id)
	{
		List<AppUser> rows = jdbc.query("SELECT * FROM app_user WHERE id = ?", MAPPER, id);
		return rows.stream().findFirst();
	}

	/** Every account, super admins first, then by name. */
	public List<AppUser> all()
	{
		return jdbc.query("SELECT * FROM app_user ORDER BY enabled DESC, "
				+ "CASE role WHEN 'SUPER_ADMIN' THEN 0 WHEN 'ADMIN' THEN 1 WHEN 'RESTAURANT_MANAGER' THEN 2 ELSE 3 END, username", MAPPER);
	}

	public long count()
	{
		Long n = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Long.class);
		return n == null ? 0 : n;
	}

	/** Super admins who can still sign in — the app must never be left without one. */
	public long countEnabledSuperAdmins()
	{
		Long n = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE role = 'SUPER_ADMIN' AND enabled = 1", Long.class);
		return n == null ? 0 : n;
	}

	public void create(String username, String displayName, String passwordHash, Role role)
	{
		create(username, displayName, passwordHash, role, false);
	}

	/** @return the new account's id */
	public long create(String username, String displayName, String passwordHash, Role role, boolean passwordChangeRequired)
	{
		jdbc.update("INSERT INTO app_user (username, display_name, password_hash, role, password_change_required) VALUES (?, ?, ?, ?, ?)",
				username, displayName, passwordHash, role.name(), passwordChangeRequired ? 1 : 0);
		Long id = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
		return id == null ? 0 : id;
	}

	public void updateProfile(long id, String displayName, Role role, boolean enabled)
	{
		jdbc.update("UPDATE app_user SET display_name = ?, role = ?, enabled = ? WHERE id = ?",
				displayName, role.name(), enabled ? 1 : 0, id);
	}

	public void updatePassword(long id, String passwordHash)
	{
		updatePassword(id, passwordHash, false);
	}

	/** A new password also unlocks the account; mustChange marks it as temporary. */
	public void updatePassword(long id, String passwordHash, boolean mustChange)
	{
		jdbc.update("UPDATE app_user SET password_hash = ?, failed_attempts = 0, locked_until = NULL, password_change_required = ? WHERE id = ?",
				passwordHash, mustChange ? 1 : 0, id);
	}

	/* ---------------- login attempts / lockout ---------------- */

	/**
	 * Counts a failed password or two-factor attempt; locks the account once the limit is
	 * reached. An expired lock is cleared first so the count starts over after the lockout.
	 */
	public void recordFailedLogin(String username, int maxAttempts, Duration lockout)
	{
		long now = System.currentTimeMillis();
		jdbc.update("""
				UPDATE app_user SET failed_attempts = 0, locked_until = NULL
				WHERE username = ? AND locked_until IS NOT NULL AND locked_until <= ?
				""", username, now);
		jdbc.update("""
				UPDATE app_user
				SET failed_attempts = failed_attempts + 1,
				    locked_until = CASE WHEN failed_attempts + 1 >= ? THEN ? ELSE locked_until END
				WHERE username = ?
				""", maxAttempts, now + lockout.toMillis(), username);
	}

	/** Called only after the FULL login (password and, when required, two-factor) succeeds. */
	public void recordSuccessfulLogin(String username)
	{
		jdbc.update("UPDATE app_user SET failed_attempts = 0, locked_until = NULL, last_login_at = ? WHERE username = ?",
				Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), username);
	}

	public void unlock(long id)
	{
		jdbc.update("UPDATE app_user SET failed_attempts = 0, locked_until = NULL WHERE id = ?", id);
	}

	/* ---------------- two-factor ---------------- */

	/** Stores a new (not yet confirmed) secret while the user scans the QR code. */
	public void savePendingTotpSecret(long id, String encryptedSecret)
	{
		jdbc.update("UPDATE app_user SET totp_secret = ?, totp_enabled = 0, totp_last_step = NULL WHERE id = ?",
				encryptedSecret, id);
	}

	public void enableTotp(long id, long acceptedStep)
	{
		jdbc.update("UPDATE app_user SET totp_enabled = 1, totp_last_step = ? WHERE id = ?", acceptedStep, id);
	}

	/**
	 * Accepts a code's time step only if it is newer than the last one used — atomically, so
	 * the same code can never be used twice, even by two simultaneous requests.
	 */
	public boolean acceptTotpStep(long id, long step)
	{
		int updated = jdbc.update("""
				UPDATE app_user SET totp_last_step = ?
				WHERE id = ? AND (totp_last_step IS NULL OR totp_last_step < ?)
				""", step, id, step);
		return updated == 1;
	}

	/** Lost phone: forces enrollment again at next login. */
	public void resetTotp(long id)
	{
		jdbc.update("UPDATE app_user SET totp_secret = NULL, totp_enabled = 0, totp_last_step = NULL WHERE id = ?", id);
	}
}
