package com.cslsm.web.salon;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** One row per day of Salon sales (salon_sales, V18). */
@Repository
@DependsOn("flyway")
public class SalonSalesRepository
{
	public record Sale(long id, LocalDate date, double cash, double card, Integer clients, String note,
					   String enteredBy, String updatedAt)
	{
		public double total()
		{
			return cash + card;
		}
	}

	private static final RowMapper<Sale> MAPPER = (rs, n) -> {
		int clients = rs.getInt("clients");
		boolean noClients = rs.wasNull();
		return new Sale(rs.getLong("id"), LocalDate.parse(rs.getString("sale_date")), rs.getDouble("cash"), rs.getDouble("card"),
				noClients ? null : clients, rs.getString("note"), rs.getString("entered_by"), rs.getString("updated_at"));
	};

	private final JdbcTemplate jdbc;

	public SalonSalesRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	public Optional<Sale> find(LocalDate date)
	{
		return jdbc.query("SELECT * FROM salon_sales WHERE sale_date = ?", MAPPER, date.toString()).stream().findFirst();
	}

	public List<Sale> between(LocalDate from, LocalDate to)
	{
		return jdbc.query("SELECT * FROM salon_sales WHERE sale_date BETWEEN ? AND ? ORDER BY sale_date DESC",
				MAPPER, from.toString(), to.toString());
	}

	/** Inserts or replaces the day's figures. */
	public void upsert(LocalDate date, double cash, double card, Integer clients, String note, String enteredBy)
	{
		jdbc.update("""
						INSERT INTO salon_sales (sale_date, cash, card, clients, note, entered_by, updated_at)
						VALUES (?, ?, ?, ?, ?, ?, ?)
						ON CONFLICT(sale_date) DO UPDATE SET cash = excluded.cash, card = excluded.card, clients = excluded.clients,
						    note = excluded.note, entered_by = excluded.entered_by, updated_at = excluded.updated_at
						""", date.toString(), cash, card, clients, note, enteredBy,
				Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
	}

	public boolean delete(LocalDate date)
	{
		return jdbc.update("DELETE FROM salon_sales WHERE sale_date = ?", date.toString()) == 1;
	}
}
