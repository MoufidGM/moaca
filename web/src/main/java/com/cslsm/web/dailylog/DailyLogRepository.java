package com.cslsm.web.dailylog;

import com.cslsm.web.dailylog.DailyLogParser.ParsedDay;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
@DependsOn("flyway")
public class DailyLogRepository
{
	public record ImportRow(long id, LocalDate logDate, String originalName, String storedName, String status,
							Double totalTtc, List<String> warnings, String message, String uploadedBy,
							String uploadedAt, String reviewedBy, String reviewedAt, String unit)
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

		public boolean hasWarnings()
		{
			return !warnings.isEmpty();
		}

		public boolean needsReview()
		{
			return hasWarnings() && reviewedBy == null && !"REJECTED".equals(status);
		}

		/** Upload time in the server's time zone, e.g. "2026-09-30 21:04". */
		public String uploadedLocal()
		{
			try
			{
				return java.time.Instant.parse(uploadedAt).atZone(java.time.ZoneId.systemDefault())
						.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
			}
			catch (RuntimeException e)
			{
				return uploadedAt;
			}
		}
	}

	private static final RowMapper<ImportRow> IMPORT_MAPPER = (rs, n) -> {
		String date = rs.getString("log_date");
		String warnings = rs.getString("warnings");
		double total = rs.getDouble("total_ttc");
		boolean noTotal = rs.wasNull();
		return new ImportRow(rs.getLong("id"), date == null ? null : LocalDate.parse(date),
				rs.getString("original_name"), rs.getString("stored_name"), rs.getString("status"),
				noTotal ? null : total,
				warnings == null || warnings.isBlank() ? List.of() : Arrays.asList(warnings.split("\n")),
				rs.getString("message"), rs.getString("uploaded_by"), rs.getString("uploaded_at"),
				rs.getString("reviewed_by"), rs.getString("reviewed_at"), rs.getString("unit"));
	};

	private final JdbcTemplate jdbc;

	public DailyLogRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	/** Total of an already imported day, if any. */
	public Optional<Double> existingTotal(LocalDate date)
	{
		List<Double> rows = jdbc.queryForList("SELECT total_ttc FROM daily_summary WHERE log_date = ?", Double.class, date.toString());
		return rows.stream().findFirst().map(v -> v == null ? 0.0 : v);
	}

	/** Same statement as the desktop app's DailyRepo.upsertSummary. */
	public void upsertDay(ParsedDay d, String filePath)
	{
		jdbc.update("""
						INSERT INTO daily_summary
						    (log_date, total_terrain, total_padel, total_gym, total_park, total_mini_golf,
						     total_ping_pong, total_academy_foot, total_taekwondo, total_shoes, total_box, total_dance,
						     total_ttc, total_ht, total_cash, total_card, total_cheque,
						     file_path, drinks_qty_total, drinks_amount_total)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, NULL, ?)
						ON CONFLICT(log_date) DO UPDATE SET
						    total_terrain = excluded.total_terrain,
						    total_padel = excluded.total_padel,
						    total_gym = excluded.total_gym,
						    total_park = excluded.total_park,
						    total_mini_golf = excluded.total_mini_golf,
						    total_ping_pong = excluded.total_ping_pong,
						    total_academy_foot = excluded.total_academy_foot,
						    total_taekwondo = excluded.total_taekwondo,
						    total_shoes = excluded.total_shoes,
						    total_box = excluded.total_box,
						    total_dance = excluded.total_dance,
						    total_ttc = excluded.total_ttc,
						    total_cash = excluded.total_cash,
						    total_card = excluded.total_card,
						    total_cheque = excluded.total_cheque,
						    file_path = excluded.file_path,
						    drinks_amount_total = excluded.drinks_amount_total
						""",
				d.date.toString(), d.terrain, d.padel, d.gym, d.park, d.miniGolf, d.pingPong, d.academy,
				d.taekwondo, d.shoes, d.box, d.dance, d.totalTtc, d.cash, d.card, d.cheque, filePath, d.drinks);
	}

	public long insertImport(LocalDate logDate, String originalName, String storedName, String sha256, Long size,
							 String status, Double totalTtc, List<String> warnings, String message, String uploadedBy)
	{
		return insertImport(logDate, originalName, storedName, sha256, size, status, totalTtc, warnings, message, uploadedBy, "CENTER");
	}

	public long insertImport(LocalDate logDate, String originalName, String storedName, String sha256, Long size,
							 String status, Double totalTtc, List<String> warnings, String message, String uploadedBy, String unit)
	{
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement("""
					INSERT INTO daily_import (log_date, original_name, stored_name, sha256, size_bytes, status,
					                          total_ttc, warnings, message, uploaded_by, uploaded_at, unit)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""", Statement.RETURN_GENERATED_KEYS);
			ps.setString(1, logDate == null ? null : logDate.toString());
			ps.setString(2, originalName);
			ps.setString(3, storedName);
			ps.setString(4, sha256);
			ps.setObject(5, size);
			ps.setString(6, status);
			ps.setObject(7, totalTtc);
			ps.setString(8, warnings == null || warnings.isEmpty() ? null : String.join("\n", warnings));
			ps.setString(9, message);
			ps.setString(10, uploadedBy);
			ps.setString(11, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
			ps.setString(12, unit);
			return ps;
		}, keys);
		return Keys.generatedId(keys);
	}

	public List<ImportRow> recentImports(int limit)
	{
		return jdbc.query("SELECT * FROM daily_import ORDER BY id DESC LIMIT ?", IMPORT_MAPPER, limit);
	}

	/** Kept files whose day falls in the range, newest day first. */
	public List<ImportRow> storedBetween(LocalDate from, LocalDate to)
	{
		return jdbc.query("SELECT * FROM daily_import WHERE stored_name IS NOT NULL AND log_date BETWEEN ? AND ? ORDER BY log_date DESC, id DESC",
				IMPORT_MAPPER, from.toString(), to.toString());
	}

	/** Recent uploads of one unit only (what the restaurant manager sees). */
	public List<ImportRow> recentImports(String unit, int limit)
	{
		return jdbc.query("SELECT * FROM daily_import WHERE unit = ? ORDER BY id DESC LIMIT ?", IMPORT_MAPPER, unit, limit);
	}

	public Optional<ImportRow> findImport(long id)
	{
		return jdbc.query("SELECT * FROM daily_import WHERE id = ?", IMPORT_MAPPER, id).stream().findFirst();
	}

	/** Another day already imported from a byte-identical file (a copied and renamed file). */
	public Optional<LocalDate> otherDateWithSameContent(String sha256, LocalDate date)
	{
		return otherDateWithSameContent(sha256, date, "CENTER");
	}

	public Optional<LocalDate> otherDateWithSameContent(String sha256, LocalDate date, String unit)
	{
		List<String> rows = jdbc.queryForList("""
				SELECT log_date FROM daily_import
				WHERE sha256 = ? AND status IN ('IMPORTED', 'REPLACED') AND log_date <> ? AND unit = ?
				ORDER BY id DESC LIMIT 1
				""", String.class, sha256, date.toString(), unit);
		return rows.stream().findFirst().map(LocalDate::parse);
	}

	/** Daily totals before a date, for the "unusually high / low" check. */
	public List<Double> recentTotals(LocalDate before, int days)
	{
		return jdbc.queryForList("""
				SELECT total_ttc FROM daily_summary
				WHERE log_date < ? AND log_date >= ? AND total_ttc > 0
				""", Double.class, before.toString(), before.minusDays(days).toString());
	}

	public List<LocalDate> missingDays(LocalDate from, LocalDate to)
	{
		List<String> present = jdbc.queryForList("SELECT log_date FROM daily_summary WHERE log_date BETWEEN ? AND ?",
				String.class, from.toString(), to.toString());
		List<LocalDate> missing = new ArrayList<>();
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1))
		{
			if (!present.contains(d.toString()))
			{
				missing.add(d);
			}
		}
		return missing;
	}

	public boolean markReviewed(long id, String reviewer)
	{
		return jdbc.update("UPDATE daily_import SET reviewed_by = ?, reviewed_at = ? WHERE id = ? AND reviewed_by IS NULL",
				reviewer, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), id) == 1;
	}

	public int countNeedingReview(LocalDate since)
	{
		Integer n = jdbc.queryForObject("""
				SELECT COUNT(*) FROM daily_import
				WHERE warnings IS NOT NULL AND reviewed_by IS NULL AND status <> 'REJECTED' AND uploaded_at >= ?
				""", Integer.class, since.toString());
		return n == null ? 0 : n;
	}
}
