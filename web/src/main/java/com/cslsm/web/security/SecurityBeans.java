package com.cslsm.web.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Security beans needed both by the web app and by the command-line user tool
 * (which runs without the web server — see SecurityConfig for the web-only part).
 */
@Configuration
public class SecurityBeans
{
	/** Argon2id with Spring Security's recommended parameters. */
	@Bean
	public PasswordEncoder passwordEncoder()
	{
		return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
	}

	/** Shared by form login and the two-factor step, which upgrades the stored login. */
	@Bean
	public SecurityContextRepository securityContextRepository()
	{
		return new DelegatingSecurityContextRepository(
				new RequestAttributeSecurityContextRepository(),
				new HttpSessionSecurityContextRepository());
	}

	@Bean
	public TotpService totpService()
	{
		return new TotpService();
	}

	@Bean
	public SecretCipher secretCipher(CslsmSecurityProperties properties)
	{
		return new SecretCipher(properties.secretKey());
	}
}
