// com/cslsm/util/AppConfig.java
package com.cslsm.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class AppConfig
{
	private static final Properties props = new Properties();

	static
	{
		try (InputStream input = AppConfig.class.getResourceAsStream("/application.properties"))
		{
			if (input == null) throw new RuntimeException("Configuration file not found: application.properties");
			props.load(input);
		}
		catch (IOException e)
		{
			throw new RuntimeException("Failed to load configuration file", e);
		}
	}

	public static String get(String key)
	{
		return props.getProperty(key);
	}

	public static String getDbPath()
	{
		String env = System.getenv("CSLSM_DB_PATH");
		if (env != null && !env.isBlank()) return expand(env);
		String p = props.getProperty("db.path");
		if (p == null || p.isBlank()) throw new IllegalStateException("Missing property: db.path");
		return expand(p);
	}

	public static Path getDbPathAsPath()
	{
		return Paths.get(getDbPath()).toAbsolutePath().normalize();
	}

	public static String getUnprocessedDir()
	{
		return expand(require("cslsm.dir.unprocessed"));
	}

	public static String getProcessedDir()
	{
		return expand(require("cslsm.dir.processed"));
	}

	public static String getFailedDir()
	{
		return expand(require("cslsm.dir.failed"));
	}

	public static Path getUnprocessedDirPath()
	{
		return Paths.get(getUnprocessedDir()).toAbsolutePath().normalize();
	}

	public static Path getProcessedDirPath()
	{
		return Paths.get(getProcessedDir()).toAbsolutePath().normalize();
	}

	public static Path getFailedDirPath()
	{
		return Paths.get(getFailedDir()).toAbsolutePath().normalize();
	}

	/**
	 * Call once at startup: creates DB parent folder and CSLSM folders if missing.
	 */
	public static void ensureAllDirectories()
	{
		try
		{
			Path dbParent = getDbPathAsPath().getParent();
			if (dbParent != null) Files.createDirectories(dbParent);
			Files.createDirectories(getUnprocessedDirPath());
			Files.createDirectories(getProcessedDirPath());
			Files.createDirectories(getFailedDirPath());
		}
		catch (IOException e)
		{
			throw new RuntimeException("Failed creating required directories: " + e.getMessage(), e);
		}
	}

	/**
	 * Base folder that relative paths in application.properties resolve against.
	 *
	 * Order: -Dcslsm.home=... (set by the packaged .app), then the CSLSM_HOME
	 * environment variable, then the current working directory (IntelliJ / terminal).
	 *
	 * This matters once the app is launched by double-clicking: macOS starts it with
	 * "/" as the working directory, so "./data/daily_logs.db" would otherwise point
	 * at the root of the disk instead of the project folder.
	 */
	public static Path getAppHome()
	{
		String h = System.getProperty("cslsm.home");
		if (h == null || h.isBlank()) h = System.getenv("CSLSM_HOME");
		if (h == null || h.isBlank()) h = System.getProperty("user.dir", ".");
		return Paths.get(expandHome(h.trim())).toAbsolutePath().normalize();
	}

	// --- helpers ---
	private static String require(String key)
	{
		String v = props.getProperty(key);
		if (v == null || v.isBlank()) throw new IllegalStateException("Missing property: " + key);
		return v;
	}

	/**
	 * Expand "~" and "${user.home}", then resolve relative paths against {@link #getAppHome()}.
	 */
	private static String expand(String raw)
	{
		Path p = Paths.get(expandHome(raw.trim()));
		if (!p.isAbsolute()) p = getAppHome().resolve(p);
		return p.normalize().toString();
	}

	/**
	 * Expand "~" and "${user.home}" only (no relative-path resolution).
	 */
	private static String expandHome(String raw)
	{
		String s = raw.trim();
		String home = System.getProperty("user.home", "");
		if (s.startsWith("~" + FileSystems.getDefault().getSeparator()))
		{
			s = home + s.substring(1);
		}
		else if (s.equals("~"))
		{
			s = home;
		}
		s = s.replace("${user.home}", home);
		return s;
	}
}
