package com.cslsm.web.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Runs the database migrations at startup.
 *
 * Done here instead of through Spring Boot's Flyway auto-configuration so the web app uses
 * exactly the same Flyway version as the desktop app (see pom.xml) while both share one
 * database. Migrations V1–V8 are byte-identical copies of the desktop app's files.
 *
 * Repositories declare @DependsOn("flyway") so no query runs before the schema is current.
 */
@Configuration
public class FlywayConfig
{
	@Bean(initMethod = "migrate")
	public Flyway flyway(DataSource dataSource)
	{
		return Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.load();
	}
}
