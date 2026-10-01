package com.cslsm.web.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Who can reach what. Every rule is enforced here on the server; the menu only mirrors it.
 *
 *   /login, static files         everyone
 *   /2fa/**                      password accepted, second factor still pending
 *   /dashboard, /income, /reports, /reserves, /activities, staff, /today, /salon, /files   ADMIN, SUPER_ADMIN
 *   uploaded originals and review                               ADMIN, SUPER_ADMIN
 *   /admin/**                    SUPER_ADMIN (users, categories, settings, audit log)
 *   /daily-logs, /expenses/import   every role (the reception uploads all three units' files,
 *                                the restaurant manager Tiki Taka's only — checked in the services)
 *   everything else (Expenses, own password)                   any fully signed-in user;
 *                                what each role may do there is checked in the services
 *
 * Controllers repeat the role checks with @PreAuthorize as a second line of defense, and
 * AccountStateInterceptor ends the session of an account that was disabled or changed role.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnWebApplication
public class SecurityConfig
{
	private static final String CSP = String.join("; ",
			"default-src 'self'",
			"script-src 'self'",
			"style-src 'self'",
			"img-src 'self' data:",
			"font-src 'self'",
			"connect-src 'self'",
			"object-src 'none'",
			"base-uri 'self'",
			"form-action 'self'",
			"frame-ancestors 'none'");

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http,
										   LoginSuccessHandler loginSuccessHandler,
										   SecurityContextRepository securityContextRepository) throws Exception
	{
		http
				.securityContext(c -> c.securityContextRepository(securityContextRepository))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/login", "/css/**", "/js/**", "/favicon.ico", "/error").permitAll()
						.requestMatchers("/2fa", "/2fa/**").hasAuthority(TwoFactor.PENDING_AUTHORITY)
						.requestMatchers("/admin/**").hasRole("SUPER_ADMIN")
						.requestMatchers("/dashboard/**", "/income/**", "/reports/**", "/reserves/**", "/activities/**",
								"/employees/**", "/payroll/**", "/today", "/salon/**", "/files/**",
								"/expenses/import/*/file", "/daily-logs/*/file", "/daily-logs/*/reviewed",
								"/restaurant/movements/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
						.requestMatchers("/daily-logs/**", "/expenses/import/**")
								.hasAnyRole("RECEPTIONIST", "RESTAURANT_MANAGER", "ADMIN", "SUPER_ADMIN")
						.requestMatchers("/restaurant/**").hasAnyRole("RESTAURANT_MANAGER", "ADMIN", "SUPER_ADMIN")
						.anyRequest().hasAnyRole("RECEPTIONIST", "RESTAURANT_MANAGER", "ADMIN", "SUPER_ADMIN"))
				.formLogin(form -> form
						.loginPage("/login")
						.successHandler(loginSuccessHandler)
						.failureUrl("/login?error")
						.permitAll())
				.logout(logout -> logout
						.logoutUrl("/logout")
						.logoutSuccessUrl("/login?logout")
						.invalidateHttpSession(true)
						.deleteCookies("CSLSM_SESSION"))
				.sessionManagement(session -> session
						.sessionFixation(fixation -> fixation.changeSessionId())
						.invalidSessionUrl("/login?expired"))
				.exceptionHandling(ex -> ex.accessDeniedHandler(new TwoFactorAccessDeniedHandler()))
				.headers(headers -> headers
						.contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
						.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
						.referrerPolicy(ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
						.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)));
		return http.build();
	}
}
