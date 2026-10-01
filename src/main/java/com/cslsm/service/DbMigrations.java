package com.cslsm.service;

import com.cslsm.util.AppConfig;
import org.flywaydb.core.Flyway;

public class DbMigrations
{

	private final String dbPath;

	public DbMigrations(String dbPath)
	{
		this.dbPath = dbPath;
	}

	public static void migrate()
	{
		Flyway flyway = Flyway.configure().dataSource("jdbc:sqlite:" + AppConfig.getDbPath() + "?busy_timeout=5000", null, null).locations("classpath:db/migration").load();
		flyway.migrate();
	}
}
