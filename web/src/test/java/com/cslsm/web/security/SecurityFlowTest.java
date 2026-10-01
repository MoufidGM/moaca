package com.cslsm.web.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end checks of sign-in, two-factor, lockout and role boundaries, plus rendering of
 * every page against sample data. Runs on a throw-away database created by the migrations.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityFlowTest
{
	private static final String PASSWORD = "correct-horse-battery-staple";

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry)
	{
		registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test.db") + "?journal_mode=WAL&busy_timeout=5000");
		registry.add("cslsm.security.secret-key", () -> "test-secret-key-that-is-long-enough-for-tests");
		registry.add("cslsm.storage.dir", () -> tempDir.resolve("files").toString());
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	UserRepository users;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	SecretCipher cipher;
	@Autowired
	TotpService totp;
	@Autowired
	JdbcTemplate jdbc;

	private static boolean seeded;

	@BeforeAll
	static void resetSeedFlag()
	{
		seeded = false;
	}

	private void createUser(String username, Role role)
	{
		if (users.findByUsername(username).isEmpty())
		{
			users.create(username, username, passwordEncoder.encode(PASSWORD), role);
		}
	}

	private void seedFinanceData()
	{
		if (seeded)
		{
			return;
		}
		LocalDate today = LocalDate.now();
		for (int i = 1; i <= 3; i++)
		{
			jdbc.update("INSERT INTO daily_summary (log_date, total_terrain, total_gym, total_ttc, total_cash, total_card, total_cheque, drinks_amount_total) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
					today.minusDays(i).toString(), 10_000, 5_000, 15_500, 9_000, 5_000, 1_500, 500);
		}
		jdbc.update("INSERT INTO expense (expense_date, category, description, amount, paid_from_storage) VALUES (?, 'Supplies', 'Balls', 1200, 1)",
				today.minusDays(1).toString());
		jdbc.update("INSERT INTO expense (expense_date, category, description, amount, paid_from_storage) VALUES (?, 'Salaries', 'Typo year', 750, 1)",
				today.plusYears(2).toString());
		jdbc.update("INSERT INTO cash_movement (movement_date, type, amount, note) VALUES (?, 'DEPOSIT', 1000, 'float')",
				today.minusDays(2).toString());
		jdbc.update("INSERT INTO safe_movement (movement_date, type, amount, note) VALUES (?, 'IN', 20000, 'weekly transfer')",
				today.minusDays(1).toString());
		seeded = true;
	}

	/* ======================= anonymous & headers ======================= */

	@Test
	void anonymousUsersAreSentToLogin() throws Exception
	{
		mvc.perform(get("/dashboard")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
		mvc.perform(get("/today")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
	}

	@Test
	void loginPageRendersWithSecurityHeaders() throws Exception
	{
		mvc.perform(get("/login"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Sign in")))
				.andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
				.andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"));
	}

	/* ======================= roles ======================= */

	@Test
	void receptionistSeesUploadsButNotMoneyPages() throws Exception
	{
		seedFinanceData();
		createUser("desk0", Role.RECEPTIONIST);
		var desk = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("desk0").roles("RECEPTIONIST");
		mvc.perform(get("/daily-logs").with(desk)).andExpect(status().isOk()).andExpect(content().string(containsString("Upload")));
		mvc.perform(get("/today").with(desk)).andExpect(status().isForbidden());
		mvc.perform(get("/dashboard").with(desk)).andExpect(status().isForbidden());
		mvc.perform(get("/reserves").with(desk)).andExpect(status().isForbidden());
		mvc.perform(get("/salon").with(desk)).andExpect(status().isForbidden());
		mvc.perform(get("/files").with(desk)).andExpect(status().isForbidden());
		mvc.perform(get("/admin/users").with(desk)).andExpect(status().isForbidden());
		mvc.perform(get("/").with(desk)).andExpect(redirectedUrl("/daily-logs"));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void adminSeesDashboardAndReservesButNotAdministration() throws Exception
	{
		seedFinanceData();
		mvc.perform(get("/dashboard"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Net profit")))
				.andExpect(content().string(containsString("dated in the future")))
				.andExpect(content().string(containsString("<svg")));
		mvc.perform(get("/reserves"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Reception balance")))
				.andExpect(content().string(containsString("weekly transfer")));
		mvc.perform(get("/today")).andExpect(status().isOk());
		mvc.perform(get("/admin/users")).andExpect(status().isForbidden());
		mvc.perform(get("/")).andExpect(redirectedUrl("/dashboard"));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void reserveAmountsAreNotPrintedAsVisibleText() throws Exception
	{
		seedFinanceData();
		MvcResult result = mvc.perform(get("/reserves")).andExpect(status().isOk()).andReturn();
		String html = result.getResponse().getContentAsString();
		// Safe balance (20,000) is only in data-value, the visible text is the mask.
		assertThat(html).contains("data-value=\"20,000\"");
		assertThat(html).doesNotContain(">20,000<");
	}

	/* ======================= full sign-in flows ======================= */

	@Test
	void receptionistSignsInWithPasswordOnly() throws Exception
	{
		createUser("desk1", Role.RECEPTIONIST);
		MvcResult login = mvc.perform(formLogin("/login").user("desk1").password(PASSWORD))
				.andExpect(redirectedUrl("/"))
				.andReturn();
		MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
		assertThat(session.getMaxInactiveInterval()).isEqualTo(15 * 60);
		mvc.perform(get("/").session(session)).andExpect(redirectedUrl("/daily-logs"));
		mvc.perform(get("/daily-logs").session(session)).andExpect(status().isOk());
		mvc.perform(get("/dashboard").session(session)).andExpect(status().isForbidden());
	}

	@Test
	void adminMustEnrollTwoFactorThenCodesCannotBeReplayed() throws Exception
	{
		createUser("boss", Role.ADMIN);

		// 1. Correct password -> only the enrollment page is reachable
		MvcResult login = mvc.perform(formLogin("/login").user("boss").password(PASSWORD))
				.andExpect(redirectedUrl("/2fa/setup"))
				.andReturn();
		MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
		mvc.perform(get("/dashboard").session(session)).andExpect(redirectedUrl("/2fa"));

		// 2. Enrollment page shows the QR code; the secret is stored encrypted
		mvc.perform(get("/2fa/setup").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<svg")));
		AppUser pending = users.findByUsername("boss").orElseThrow();
		assertThat(pending.totpSecretEncrypted()).startsWith("v1:");
		String secret = cipher.decrypt(pending.totpSecretEncrypted());

		// 3. Confirm with a valid code -> fully signed in
		long step = Instant.now().getEpochSecond() / 30;
		String code = totp.generate(Base32.decode(secret), step);
		mvc.perform(post("/2fa/setup").session(session).with(csrf()).param("code", code))
				.andExpect(redirectedUrl("/"));
		mvc.perform(get("/dashboard").session(session)).andExpect(status().isOk());
		assertThat(users.findByUsername("boss").orElseThrow().totpEnabled()).isTrue();

		// 4. Next sign-in: the same code is refused (replay), the next one is accepted
		mvc.perform(post("/logout").session(session).with(csrf()));
		MvcResult second = mvc.perform(formLogin("/login").user("boss").password(PASSWORD))
				.andExpect(redirectedUrl("/2fa"))
				.andReturn();
		MockHttpSession session2 = (MockHttpSession) second.getRequest().getSession(false);
		mvc.perform(post("/2fa").session(session2).with(csrf()).param("code", code))
				.andExpect(redirectedUrl("/2fa?error"));
		String nextCode = totp.generate(Base32.decode(secret), step + 1);
		mvc.perform(post("/2fa").session(session2).with(csrf()).param("code", nextCode))
				.andExpect(redirectedUrl("/"));
		mvc.perform(get("/dashboard").session(session2)).andExpect(status().isOk());
		assertThat(session2.getMaxInactiveInterval()).isEqualTo(60 * 60);
	}

	@Test
	void fiveWrongPasswordsLockTheAccount() throws Exception
	{
		createUser("victim", Role.RECEPTIONIST);
		for (int i = 0; i < 5; i++)
		{
			mvc.perform(formLogin("/login").user("victim").password("wrong-password-" + i))
					.andExpect(redirectedUrl("/login?error"));
		}
		// Even the right password is refused while locked
		mvc.perform(formLogin("/login").user("victim").password(PASSWORD))
				.andExpect(redirectedUrl("/login?error"));
		assertThat(users.findByUsername("victim").orElseThrow().isLocked(System.currentTimeMillis())).isTrue();
	}

	@Test
	void fiveWrongCodesLockTheAccountAndEndTheSession() throws Exception
	{
		createUser("guesser", Role.SUPER_ADMIN);
		AppUser user = users.findByUsername("guesser").orElseThrow();
		String secret = totp.newSecret();
		users.savePendingTotpSecret(user.id(), cipher.encrypt(secret));
		users.enableTotp(user.id(), 0);

		MvcResult login = mvc.perform(formLogin("/login").user("guesser").password(PASSWORD))
				.andExpect(redirectedUrl("/2fa"))
				.andReturn();
		MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
		for (int i = 0; i < 4; i++)
		{
			mvc.perform(post("/2fa").session(session).with(csrf()).param("code", "000000"))
					.andExpect(redirectedUrl("/2fa?error"));
		}
		mvc.perform(post("/2fa").session(session).with(csrf()).param("code", "000000"))
				.andExpect(redirectedUrl("/login?locked"));
		assertThat(users.findByUsername("guesser").orElseThrow().isLocked(System.currentTimeMillis())).isTrue();
	}
}
