package com.cslsm.web.expenses;

import com.cslsm.web.expenses.AttachmentRepository.Attachment;
import com.cslsm.web.expenses.ExpenseModels.ExpenseRuleException;
import com.cslsm.web.support.Actor;
import com.cslsm.web.support.AuditService;
import com.cslsm.web.support.FileStorage;
import com.cslsm.web.support.FileTypes;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Receipts attached to expenses: photos (JPEG, PNG, WebP, HEIC) or PDFs, max 10 MB each,
 * max 10 per expense. The type is decided from the file's content, never from its name.
 */
@Service
public class AttachmentService
{
	static final long MAX_BYTES = 10L * 1024 * 1024;
	static final int MAX_PER_EXPENSE = 10;

	private final AttachmentRepository attachments;
	private final FileStorage storage;
	private final AuditService audit;

	public AttachmentService(AttachmentRepository attachments, FileStorage storage, AuditService audit)
	{
		this.attachments = attachments;
		this.storage = storage;
		this.audit = audit;
	}

	/** Checks every file before anything is stored, so a bad file never leaves half an upload behind. */
	public void validate(List<MultipartFile> files, int alreadyAttached)
	{
		List<MultipartFile> real = nonEmpty(files);
		if (alreadyAttached + real.size() > MAX_PER_EXPENSE)
		{
			throw new ExpenseRuleException("At most " + MAX_PER_EXPENSE + " receipts per expense.");
		}
		for (MultipartFile f : real)
		{
			if (f.getSize() > MAX_BYTES)
			{
				throw new ExpenseRuleException(FileTypes.safeName(f.getOriginalFilename()) + " is larger than 10 MB.");
			}
			try
			{
				if (FileTypes.sniffReceipt(f.getBytes()) == null)
				{
					throw new ExpenseRuleException(FileTypes.safeName(f.getOriginalFilename())
							+ " is not a photo or a PDF. Receipts must be JPEG, PNG, WebP, HEIC or PDF.");
				}
			}
			catch (IOException e)
			{
				throw new ExpenseRuleException("The upload was interrupted. Try again.");
			}
		}
	}

	/**
	 * Stores the files for an expense. Call inside the expense transaction after validate();
	 * returns the stored names so the caller can remove them if the transaction fails.
	 */
	public List<String> store(long expenseId, List<MultipartFile> files, Actor actor) throws IOException
	{
		List<String> stored = new java.util.ArrayList<>();
		for (MultipartFile f : nonEmpty(files))
		{
			byte[] bytes = f.getBytes();
			FileTypes.Kind kind = FileTypes.sniffReceipt(bytes);
			if (kind == null)
			{
				throw new ExpenseRuleException("Unsupported receipt file.");
			}
			String name = storage.store("receipts", bytes, kind.extension);
			stored.add(name);
			String original = FileTypes.safeName(f.getOriginalFilename());
			attachments.insert(expenseId, original, name, kind.contentType, bytes.length, FileTypes.sha256Hex(bytes), actor.username());
			audit.record(actor, "RECEIPT_ADD", "expense", expenseId, original + " (" + kind.contentType + ", " + bytes.length + " bytes)");
		}
		return stored;
	}

	public byte[] content(Attachment a) throws IOException
	{
		return storage.read(a.storedName());
	}

	/** Removes files from disk after their rows were deleted in a committed transaction. */
	public void deleteFiles(List<Attachment> list)
	{
		for (Attachment a : list)
		{
			storage.deleteQuietly(a.storedName());
		}
	}

	public void deleteStoredQuietly(List<String> storedNames)
	{
		storedNames.forEach(storage::deleteQuietly);
	}

	static List<MultipartFile> nonEmpty(List<MultipartFile> files)
	{
		return files == null ? List.of() : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
	}
}
