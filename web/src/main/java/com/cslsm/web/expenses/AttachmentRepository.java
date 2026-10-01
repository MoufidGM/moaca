package com.cslsm.web.expenses;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Repository
@DependsOn("flyway")
public class AttachmentRepository
{
	public record Attachment(long id, long expenseId, String originalName, String storedName, String contentType,
							 long sizeBytes, String uploadedBy, String uploadedAt)
	{
		public boolean isImage()
		{
			return contentType.startsWith("image/") && !contentType.equals("image/heic");
		}

		public String sizeLabel()
		{
			return sizeBytes >= 1024 * 1024
					? String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0))
					: Math.max(1, sizeBytes / 1024) + " KB";
		}
	}

	private static final RowMapper<Attachment> MAPPER = (rs, n) -> new Attachment(
			rs.getLong("id"), rs.getLong("expense_id"), rs.getString("original_name"), rs.getString("stored_name"),
			rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("uploaded_by"), rs.getString("uploaded_at"));

	private final JdbcTemplate jdbc;

	public AttachmentRepository(JdbcTemplate jdbc)
	{
		this.jdbc = jdbc;
	}

	public void insert(long expenseId, String originalName, String storedName, String contentType, long size,
					   String sha256, String uploadedBy)
	{
		jdbc.update("""
						INSERT INTO expense_attachment (expense_id, original_name, stored_name, content_type, size_bytes,
						                                sha256, uploaded_by, uploaded_at)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?)
						""", expenseId, originalName, storedName, contentType, size, sha256, uploadedBy,
				Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
	}

	public List<Attachment> forExpense(long expenseId)
	{
		return jdbc.query("SELECT * FROM expense_attachment WHERE expense_id = ? ORDER BY id", MAPPER, expenseId);
	}

	public Optional<Attachment> find(long id)
	{
		return jdbc.query("SELECT * FROM expense_attachment WHERE id = ?", MAPPER, id).stream().findFirst();
	}

	public int countFor(long expenseId)
	{
		Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM expense_attachment WHERE expense_id = ?", Integer.class, expenseId);
		return n == null ? 0 : n;
	}

	public void delete(long id)
	{
		jdbc.update("DELETE FROM expense_attachment WHERE id = ?", id);
	}

	public void deleteForExpense(long expenseId)
	{
		jdbc.update("DELETE FROM expense_attachment WHERE expense_id = ?", expenseId);
	}
}
