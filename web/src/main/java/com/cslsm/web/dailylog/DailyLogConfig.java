package com.cslsm.web.dailylog;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

@Configuration
public class DailyLogConfig
{
	/** Cell positions from daily-log-layout.properties (an unchanged copy of the desktop layout). */
	@Bean
	public DailyLogParser dailyLogParser() throws IOException
	{
		Properties layout = new Properties();
		try (InputStream in = DailyLogConfig.class.getResourceAsStream("/daily-log-layout.properties"))
		{
			if (in == null)
			{
				throw new IllegalStateException("daily-log-layout.properties missing from the classpath");
			}
			layout.load(in);
		}
		return new DailyLogParser(new DailyLogParser.Layout(layout));
	}

	@Bean
	public UnitLogParser restaurantLogParser() throws IOException
	{
		return new UnitLogParser(new UnitLogParser.Layout("RESTAURANT", load("/restaurant-log-layout.properties")));
	}

	@Bean
	public UnitLogParser salonLogParser() throws IOException
	{
		return new UnitLogParser(new UnitLogParser.Layout("SALON", load("/salon-log-layout.properties")));
	}

	private static Properties load(String resource) throws IOException
	{
		Properties p = new Properties();
		try (InputStream in = DailyLogConfig.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				throw new IllegalStateException(resource + " missing from the classpath");
			}
			p.load(in);
		}
		return p;
	}
}
