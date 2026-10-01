package com.cslsm.web;

import com.cslsm.web.cli.UserAdminCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CslsmWebApplication
{
	public static void main(String[] args)
	{
		SpringApplication app = new SpringApplication(CslsmWebApplication.class);

		// User-administration commands (--create-user, --reset-password, ...) run without
		// starting the web server, so they work while the service is running.
		if (UserAdminCli.isCliInvocation(args))
		{
			app.setWebApplicationType(WebApplicationType.NONE);
		}
		app.run(args);
	}
}
