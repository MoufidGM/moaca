package com.cslsm.service;

import com.cslsm.model.DailySummary;
import com.cslsm.model.LineItem;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;

import java.nio.file.Path;
import java.nio.file.Paths;

public class HibernateUtil
{

	// 1. Remove the eager initialization from the main class
	// private static final SessionFactory SESSION_FACTORY = build();

	private static SessionFactory build()
	{
		Path dbFile = Paths.get("cslsm_daily.db").toAbsolutePath();

		StandardServiceRegistry registry = new StandardServiceRegistryBuilder().applySetting("hibernate.connection.driver_class", "org.sqlite.JDBC").applySetting("hibernate.connection.url", "jdbc:sqlite:" + dbFile) // same path as Flyway
				.applySetting("hibernate.hbm2ddl.auto", "validate").applySetting("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect").applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy").applySetting("hibernate.show_sql", "false").build();

		return new MetadataSources(registry).addAnnotatedClass(DailySummary.class).addAnnotatedClass(LineItem.class).buildMetadata().buildSessionFactory();
	}

	// 3. The accessor method now returns the lazily initialized field
	public static SessionFactory sf()
	{
		return SessionFactoryHolder.SESSION_FACTORY;
	}

	// 2. Use a private static inner class to hold the SessionFactory
	// This class will only be loaded when SessionFactoryHolder is first referenced,
	// ensuring lazy, thread-safe initialization.
	private static class SessionFactoryHolder
	{
		private static final SessionFactory SESSION_FACTORY = build();
	}
}