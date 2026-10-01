package com.cslsm.web.reserves;

import com.cslsm.web.finance.FinanceModels.BankAccount;
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
 * Writes to bank_movement (V19): what the app cannot derive on its own about the two bank
 * accounts — other money in, bank fees and other payments, transfers between the accounts,
 * and corrections to the statement balance.
 */
@Repository
@DependsOn("flyway")
public class BankRepository
{
	public record StoredMovement(long id, BankAccount account, LocalDate date, String type, double amount, String note)
	{
	}

	private final JdbcTemplate jdbc;

	public BankRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	public long insert(BankAccount account, LocalDate date, String type, double amount, String note)
	{
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement(
					"INSERT INTO bank_movement (account, movement_date, type, amount, note) VALUES (?, ?, ?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setString(1, account.name());
			ps.setString(2, date.toString());
			ps.setString(3, type);
			ps.setDouble(4, amount);
			ps.setString(5, note);
			return ps;
		}, keys);
		return Keys.generatedId(keys);
	}

	public Optional<StoredMovement> find(long id)
	{
		return jdbc.query("SELECT id, account, movement_date, type, amount, note FROM bank_movement WHERE id = ?",
				(rs, n) -> new StoredMovement(rs.getLong(1), BankAccount.valueOf(rs.getString(2)), LocalDate.parse(rs.getString(3)),
						rs.getString(4), rs.getDouble(5), rs.getString(6)), id).stream().findFirst();
	}

	public boolean delete(long id)
	{
		return jdbc.update("DELETE FROM bank_movement WHERE id = ?", id) == 1;
	}
}
