package com.cslsm.web.expenses;

import com.cslsm.web.support.Keys;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** Uploaded expense sheets (V22): the file kept on disk and what came out of it. */
@Repository
@DependsOn("flyway")
public class ExpenseImportRepository
{
	public record ImportedSheet(long id, String unit, String originalName, String storedName, int rowsSaved, int rowsSkipped,
								double total, LocalDate fromDate, LocalDate toDate, String status, String uploadedBy, String uploadedAt)
	{
		public String unitLabel()
		{
			return switch (unit == null ? "" : unit)
			{
				case "RESTAURANT" -> "Tiki Taka";
				case "SALON" -> "Salon";
				default -> "Center";
			};
		}

		/** The date the file sorts under: the latest expense in it. */
		public LocalDate date()
		{
			return toDate != null ? toDate : LocalDate.parse(uploadedAt.substring(0, 10));
		}
	}

	private static final RowMapper<ImportedSheet> MAPPER = (rs, n) -> new ImportedSheet(rs.getLong("id"), rs.getString("unit"),
			rs.getString("original_name"), rs.getString("stored_name"), rs.getInt("rows_saved"), rs.getInt("rows_skipped"),
			rs.getDouble("total"), date(rs.getString("from_date")), date(rs.getString("to_date")), rs.getString("status"),
			rs.getString("uploaded_by"), rs.getString("uploaded_at"));

	private static LocalDate date(String s)
	{
		return s == null ? null : LocalDate.parse(s);
	}

	private final JdbcTemplate jdbc;

	public ExpenseImportRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	public long insert(String unit, String originalName, String storedName, String sha256, long size, int saved, int skipped,
					   double total, LocalDate from, LocalDate to, String status, String uploadedBy)
	{
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement("""
					INSERT INTO expense_import (unit, original_name, stored_name, sha256, size_bytes, rows_saved, rows_skipped, total,
					                            from_date, to_date, status, uploaded_by, uploaded_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""", Statement.RETURN_GENERATED_KEYS);
			ps.setString(1, unit);
			ps.setString(2, originalName);
			ps.setString(3, storedName);
			ps.setString(4, sha256);
			ps.setLong(5, size);
			ps.setInt(6, saved);
			ps.setInt(7, skipped);
			ps.setDouble(8, total);
			ps.setString(9, from == null ? null : from.toString());
			ps.setString(10, to == null ? null : to.toString());
			ps.setString(11, status);
			ps.setString(12, uploadedBy);
			ps.setString(13, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
			return ps;
		}, keys);
		return Keys.generatedId(keys);
	}

	public Optional<ImportedSheet> find(long id)
	{
		return jdbc.query("SELECT * FROM expense_import WHERE id = ?", MAPPER, id).stream().findFirst();
	}

	/** Sheets whose latest expense date (or upload date) falls in the range. */
	public List<ImportedSheet> between(LocalDate from, LocalDate to)
	{
		return jdbc.query("SELECT * FROM expense_import WHERE COALESCE(to_date, substr(uploaded_at, 1, 10)) BETWEEN ? AND ?"
				+ " ORDER BY COALESCE(to_date, substr(uploaded_at, 1, 10)) DESC, id DESC", MAPPER, from.toString(), to.toString());
	}
}
