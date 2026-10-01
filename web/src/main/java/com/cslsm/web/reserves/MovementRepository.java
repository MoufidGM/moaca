package com.cslsm.web.reserves;

import com.cslsm.web.support.Keys;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Writes to the two movement tables the desktop app already uses.
 *
 *   cash_movement (reception): DEPOSIT (+), WITHDRAWAL (−), BANK (− sent to bank)
 *   safe_movement (safe):      IN (+ from reception, also − reception), BANK (−), OUT (− payment),
 *                              ADJUST_IN (+) / ADJUST_OUT (−) count corrections, which do not
 *                              touch the reception balance
 */
@Repository
@DependsOn("flyway")
public class MovementRepository
{
	public enum Table
	{
		RECEPTION("cash_movement"), SAFE("safe_movement");

		final String sql;

		Table(String sql)
		{
			this.sql = sql;
		}
	}

	public record StoredMovement(long id, Table table, LocalDate date, String type, double amount, String note)
	{
	}

	private final JdbcTemplate jdbc;

	public MovementRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	public long insert(Table table, LocalDate date, String type, double amount, String note)
	{
		return insert(table, date, type, amount, note, "RECEPTION");
	}

	/**
	 * @param till RECEPTION or RESTAURANT: which till a cash_movement concerns, or which till a
	 *             safe_movement IN came from (stored in safe_movement.source)
	 */
	public long insert(Table table, LocalDate date, String type, double amount, String note, String till)
	{
		String column = table == Table.SAFE ? "source" : "till";
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement(
					"INSERT INTO " + table.sql + " (movement_date, type, amount, note, " + column + ") VALUES (?, ?, ?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setString(1, date.toString());
			ps.setString(2, type);
			ps.setDouble(3, amount);
			ps.setString(4, note);
			ps.setString(5, till);
			return ps;
		}, keys);
		return Keys.generatedId(keys);
	}

	public Optional<StoredMovement> find(Table table, long id)
	{
		return jdbc.query("SELECT id, movement_date, type, amount, note FROM " + table.sql + " WHERE id = ?",
				(rs, n) -> new StoredMovement(rs.getLong(1), table, LocalDate.parse(rs.getString(2)),
						rs.getString(3), rs.getDouble(4), rs.getString(5)), id).stream().findFirst();
	}

	public boolean delete(Table table, long id)
	{
		return jdbc.update("DELETE FROM " + table.sql + " WHERE id = ?", id) == 1;
	}
}
