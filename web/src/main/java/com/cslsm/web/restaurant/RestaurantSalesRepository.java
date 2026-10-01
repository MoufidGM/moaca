package com.cslsm.web.restaurant;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** One row per day of Tiki Taka sales (restaurant_sales, V16). */
@Repository
@DependsOn("flyway")
public class RestaurantSalesRepository
{
	public record Sale(long id, LocalDate date, double cash, double card, Integer covers, String note,
					   String enteredBy, String updatedAt, double onAccount)
	{
		public double total()
		{
			return cash + card;
		}
	}

	private static final RowMapper<Sale> MAPPER = (rs, n) -> {
		int covers = rs.getInt("covers");
		boolean noCovers = rs.wasNull();
		return new Sale(rs.getLong("id"), LocalDate.parse(rs.getString("sale_date")), rs.getDouble("cash"), rs.getDouble("card"),
				noCovers ? null : covers, rs.getString("note"), rs.getString("entered_by"), rs.getString("updated_at"), rs.getDouble("on_account"));
	};

	private final JdbcTemplate jdbc;

	public RestaurantSalesRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	public Optional<Sale> find(LocalDate date)
	{
		return jdbc.query("SELECT * FROM restaurant_sales WHERE sale_date = ?", MAPPER, date.toString()).stream().findFirst();
	}

	public List<Sale> between(LocalDate from, LocalDate to)
	{
		return jdbc.query("SELECT * FROM restaurant_sales WHERE sale_date BETWEEN ? AND ? ORDER BY sale_date DESC",
				MAPPER, from.toString(), to.toString());
	}

	/** Inserts or replaces the day's figures. */
	public void upsert(LocalDate date, double cash, double card, Integer covers, String note, String enteredBy)
	{
		upsert(date, cash, card, 0, covers, note, enteredBy);
	}

	/** onAccount: the family's meals of the day at menu value (restaurant), kept apart from cash and card. */
	public void upsert(LocalDate date, double cash, double card, double onAccount, Integer covers, String note, String enteredBy)
	{
		jdbc.update("""
						INSERT INTO restaurant_sales (sale_date, cash, card, on_account, covers, note, entered_by, updated_at)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?)
						ON CONFLICT(sale_date) DO UPDATE SET cash = excluded.cash, card = excluded.card, on_account = excluded.on_account,
						    covers = excluded.covers, note = excluded.note, entered_by = excluded.entered_by, updated_at = excluded.updated_at
						""", date.toString(), cash, card, onAccount, covers, note, enteredBy,
				Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
	}

	public boolean delete(LocalDate date)
	{
		return jdbc.update("DELETE FROM restaurant_sales WHERE sale_date = ?", date.toString()) == 1;
	}

	public double sum(String column, LocalDate from, LocalDate to)
	{
		if (!column.equals("cash") && !column.equals("card"))
		{
			throw new IllegalArgumentException(column);
		}
		Double v = jdbc.queryForObject("SELECT COALESCE(SUM(" + column + "), 0) FROM restaurant_sales WHERE sale_date BETWEEN ? AND ?",
				Double.class, from.toString(), to.toString());
		return v == null ? 0 : v;
	}
}
