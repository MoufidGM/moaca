package com.cslsm.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Injected wherever "today" matters, so tests can pin the date. */
@Configuration
public class ClockConfig
{
	@Bean
	public Clock clock()
	{
		return Clock.systemDefaultZone();
	}
}
