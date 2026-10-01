package com.cslsm.app;

import com.cslsm.service.DbMigrations;
import com.cslsm.ui.MainView;
import com.cslsm.util.AppConfig;
import com.cslsm.util.AppServices;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.sql.SQLException;

public class App extends Application
{
	public static void main(String[] args)
	{
		launch(args);
	}

	@Override
	public void start(Stage stage) throws SQLException
	{
		AppConfig.ensureAllDirectories();
		// run Flyway before UI comes up
		DbMigrations.migrate();

		MainView root = new MainView(getHostServices()); // pass host services
		Scene scene = new Scene(root, 1280, 900);
		stage.setScene(scene);
		stage.setTitle("CSLSM Daily Logs");

		// ... build UI ...
		AppServices.IMPORTS.startAutoImport();
		stage.show();


	}
}
