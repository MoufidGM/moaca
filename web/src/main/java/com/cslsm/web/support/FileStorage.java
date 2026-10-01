package com.cslsm.web.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Stores uploaded files under cslsm.storage.dir with random names, readable only by the
 * service account. Callers never choose the path: names come from here, and every lookup is
 * checked to stay inside the storage folder.
 */
@Component
public class FileStorage
{
	private static final Logger log = LoggerFactory.getLogger(FileStorage.class);
	private static final Set<String> EXTENSIONS = Set.of("xlsx", "xls", "pdf", "jpg", "png", "webp", "heic");

	private final Path root;

	public FileStorage(@Value("${cslsm.storage.dir}") String dir) throws IOException
	{
		this.root = Paths.get(dir).toAbsolutePath().normalize();
		Files.createDirectories(root);
	}

	/**
	 * @param area      sub-folder, e.g. "daily-logs" or "receipts"
	 * @param extension one of the allowed extensions, without the dot
	 * @return the stored name (relative path) to keep in the database
	 */
	public String store(String area, byte[] content, String extension) throws IOException
	{
		if (!EXTENSIONS.contains(extension) || !area.matches("[a-z-]+"))
		{
			throw new IllegalArgumentException("Refusing to store ." + extension + " in " + area);
		}
		LocalDate today = LocalDate.now();
		String relative = area + "/" + today.getYear() + "/" + String.format("%02d", today.getMonthValue())
				+ "/" + UUID.randomUUID() + "." + extension;
		Path target = resolve(relative);
		Files.createDirectories(target.getParent());
		Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		try
		{
			Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
		}
		catch (UnsupportedOperationException ignored)
		{
			// Non-POSIX file system (development on Windows); the server is POSIX.
		}
		return relative;
	}

	public byte[] read(String relative) throws IOException
	{
		return Files.readAllBytes(resolve(relative));
	}

	public Path absolute(String relative)
	{
		return resolve(relative);
	}

	public void deleteQuietly(String relative)
	{
		if (relative == null)
		{
			return;
		}
		try
		{
			Files.deleteIfExists(resolve(relative));
		}
		catch (IOException | IllegalArgumentException e)
		{
			log.warn("Could not delete stored file {}: {}", relative, e.getMessage());
		}
	}

	private Path resolve(String relative)
	{
		Path p = root.resolve(relative).normalize();
		if (!p.startsWith(root))
		{
			throw new IllegalArgumentException("Stored path escapes the storage folder");
		}
		return p;
	}
}
