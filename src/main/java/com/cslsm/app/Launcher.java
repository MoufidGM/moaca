package com.cslsm.app;

/**
 * Entry point for the packaged application.
 *
 * A class that extends javafx.application.Application cannot be the main class
 * when JavaFX is on the classpath (the JVM refuses with "JavaFX runtime components
 * are missing"). This plain launcher avoids that, so the .app bundle can start
 * without any module-path configuration.
 */
public class Launcher
{
	public static void main(String[] args)
	{
		App.main(args);
	}
}
