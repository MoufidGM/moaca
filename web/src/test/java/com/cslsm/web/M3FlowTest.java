package com.cslsm.web;

import com.cslsm.web.finance.FinanceRepository;
import com.cslsm.web.security.Role;
import com.cslsm.web.security.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 3 end to end: the Income page, the reports and their downloads, and the
 * Administration page (accounts with temporary passwords, categories, the year-mode
 * setting, the audit log viewer).
 */
@SpringBootTest
@AutoConfigureMockMvc
class M3FlowTest
{
	private static final LocalDate TODAY = LocalDate.now();
	private static final String PASSWORD = "a-long-enough-password";

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry)
	{
		registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("m3.db") + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=true");
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
	JdbcTemplate jdbc;
	@Autowired
	FinanceRepository finance;

	private static final RequestPostProcessor ROOT = user("root").roles("SUPER_ADMIN");
	private static final RequestPostProcessor FIN = user("fin").roles("ADMIN");
	private static final RequestPostProcessor DESK = user("desk").roles("RECEPTIONIST");

	private static boolean seeded;

	@BeforeEach
	void seed()
	{
		create("root", "Root", Role.SUPER_ADMIN);
		create("fin", "Finance", Role.ADMIN);
		create("desk", "Desk", Role.RECEPTIONIST);
		if (seeded)
		{
			return;
		}
		// Ten days of logs ending yesterday, one of them missing; an expense; restaurant sales.
		for (int i = 1; i <= 10; i++)
		{
			if (i == 4)
			{
				continue;
			}
			LocalDate d = TODAY.minusDays(i);
			jdbc.update("INSERT INTO daily_summary (log_date, total_terrain, total_gym, total_ttc, total_cash, total_card, total_cheque, drinks_amount_total)"
					+ " VALUES (?, 3000, 1000, 4000, 3500, 500, 0, 200)", d.toString());
		}
		jdbc.update("INSERT INTO expense (expense_date, category, description, amount, activity, paid_from_storage) VALUES (?, 'Supplies', 'Balls', 1500, 'Gym', 1)",
				TODAY.minusDays(2).toString());
		jdbc.update("INSERT INTO restaurant_sales (sale_date, cash, card, entered_by, updated_at) VALUES (?, 800, 200, 'chef', '2026-01-01T00:00:00Z')",
				TODAY.minusDays(2).toString());
		seeded = true;
	}

	private void create(String username, String name, Role role)
	{
		if (users.findByUsername(username).isEmpty())
		{
			users.create(username, name, passwordEncoder.encode(PASSWORD), role);
		}
	}

	/* ======================= income ======================= */

	@Test
	void incomePageShowsEveryPeriodAndTheComparison() throws Exception
	{
		mvc.perform(get("/income").with(FIN))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Income per day")))
				.andExpect(content().string(containsString("<svg")));
		mvc.perform(get("/income").with(FIN).param("unit", "day").param("date", TODAY.minusDays(2).toString()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("5,200")))                // 4,200 from the log + 1,000 at the restaurant
				.andExpect(content().string(containsString("Tiki Taka")));
		mvc.perform(get("/income").with(FIN).param("unit", "year").param("compare", "true"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Income per month")))
				.andExpect(content().string(containsString("Comparing")));
		mvc.perform(get("/income").with(FIN).param("unit", "custom")
						.param("from", TODAY.minusDays(10).toString()).param("to", TODAY.minusDays(1).toString()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("38,800")))            // 9 days × 4,200 + 1,000 at the restaurant
				.andExpect(content().string(containsString("1 day(s) without a log file")));
		// Garbage dates fall back to this month instead of failing
		mvc.perform(get("/income").with(FIN).param("unit", "week").param("date", "not-a-date")).andExpect(status().isOk());
		mvc.perform(get("/income").with(DESK)).andExpect(status().isForbidden());
	}

	/* ======================= reports ======================= */

	@Test
	void reportsRenderAndDownload() throws Exception
	{
		for (String key : new String[]{"pnl", "expenses-category", "expenses-activity", "payments", "yoy", "activities"})
		{
			mvc.perform(get("/reports").with(FIN).param("report", key).param("year", String.valueOf(TODAY.getYear())))
					.andExpect(status().isOk())
					.andExpect(content().string(containsString("Download Excel")));
		}
		mvc.perform(get("/reports").with(FIN).param("report", "pnl"))
				.andExpect(content().string(containsString("Net profit")))
				.andExpect(content().string(containsString("Tiki Taka (restaurant)")));

		MvcResult csv = mvc.perform(get("/reports/export").with(FIN).param("report", "pnl")
						.param("year", String.valueOf(TODAY.getYear())).param("format", "csv"))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", containsString("cslsm-pnl-" + TODAY.getYear() + ".csv")))
				.andReturn();
		String text = new String(csv.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
		assertThat(text).startsWith("﻿").contains("Profit & loss").contains("Net profit").contains("Total revenue");

		MvcResult xlsx = mvc.perform(get("/reports/export").with(FIN).param("report", "yoy")
						.param("year", String.valueOf(TODAY.getYear())))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", containsString("spreadsheetml")))
				.andReturn();
		byte[] bytes = xlsx.getResponse().getContentAsByteArray();
		assertThat(bytes[0]).isEqualTo((byte) 'P');
		assertThat(bytes[1]).isEqualTo((byte) 'K');
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'REPORT_EXPORT' AND username = 'fin'", Integer.class)).isEqualTo(2);

		mvc.perform(get("/reports").with(DESK)).andExpect(status().isForbidden());
		mvc.perform(get("/reports/export").with(DESK).param("report", "pnl").param("year", "2026")).andExpect(status().isForbidden());
	}

	/* ======================= administration: accounts ======================= */

	@Test
	void superAdminCreatesAccountsWithTemporaryPasswords() throws Exception
	{
		mvc.perform(get("/admin").with(FIN)).andExpect(status().isForbidden());
		mvc.perform(get("/admin").with(ROOT)).andExpect(status().isOk()).andExpect(content().string(containsString("Accounts")));

		// Bad input is shown back on the form
		mvc.perform(post("/admin/users/new").with(ROOT).with(csrf())
						.param("username", "desk").param("role", "RECEPTIONIST").param("password", PASSWORD).param("repeat", PASSWORD))
				.andExpect(model().attribute("error", containsString("already an account")));
		mvc.perform(post("/admin/users/new").with(ROOT).with(csrf())
						.param("username", "newdesk").param("role", "RECEPTIONIST").param("password", "short").param("repeat", "short"))
				.andExpect(model().attribute("error", containsString("at least 12")));

		mvc.perform(post("/admin/users/new").with(ROOT).with(csrf())
						.param("username", "newdesk").param("displayName", "Desk Three").param("role", "RECEPTIONIST")
						.param("password", "temporary-pass-1").param("repeat", "temporary-pass-1"))
				.andExpect(status().is3xxRedirection())
				.andExpect(flash().attribute("flashOk", containsString("temporary password")));
		long id = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'newdesk'", Long.class);
		assertThat(jdbc.queryForObject("SELECT password_change_required FROM app_user WHERE id = ?", Integer.class, id)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'USER_CREATE' AND entity_id = ?", Integer.class, String.valueOf(id))).isEqualTo(1);

		// The person signs in and can only change the password until they do
		MvcResult login = mvc.perform(formLogin("/login").user("newdesk").password("temporary-pass-1"))
				.andExpect(redirectedUrl("/")).andReturn();
		MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
		mvc.perform(get("/daily-logs").session(session)).andExpect(redirectedUrl("/account/password"));
		mvc.perform(get("/expenses").session(session)).andExpect(redirectedUrl("/account/password"));
		mvc.perform(get("/account/password").session(session)).andExpect(status().isOk())
				.andExpect(content().string(containsString("set by the administrator")));
		mvc.perform(post("/account/password").session(session).with(csrf())
						.param("current", "wrong-current-password").param("password", "my-own-password-1").param("repeat", "my-own-password-1"))
				.andExpect(model().attribute("error", containsString("current password is wrong")));
		mvc.perform(post("/account/password").session(session).with(csrf())
						.param("current", "temporary-pass-1").param("password", "my-own-password-1").param("repeat", "my-own-password-1"))
				.andExpect(redirectedUrl("/"));
		assertThat(jdbc.queryForObject("SELECT password_change_required FROM app_user WHERE id = ?", Integer.class, id)).isZero();
		mvc.perform(get("/daily-logs").session(session)).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'PASSWORD_CHANGE' AND username = 'newdesk'", Integer.class)).isEqualTo(1);

		// Switched off by the super admin: the open session ends at the next click
		mvc.perform(post("/admin/users/" + id).with(ROOT).with(csrf())
						.param("displayName", "Desk Three").param("role", "RECEPTIONIST").param("enabled", "false"))
				.andExpect(redirectedUrl("/admin"));
		mvc.perform(get("/daily-logs").session(session)).andExpect(redirectedUrl("/login?expired"));
		mvc.perform(formLogin("/login").user("newdesk").password("my-own-password-1")).andExpect(redirectedUrl("/login?error"));

		// A forgotten password gets a temporary one, and unlocks the account
		mvc.perform(post("/admin/users/" + id + "/password").with(ROOT).with(csrf())
						.param("password", "another-temp-pass").param("repeat", "another-temp-pass"))
				.andExpect(redirectedUrl("/admin/users/" + id))
				.andExpect(flash().attribute("flashOk", containsString("Temporary password set")));
		assertThat(jdbc.queryForObject("SELECT password_change_required FROM app_user WHERE id = ?", Integer.class, id)).isEqualTo(1);
		mvc.perform(get("/admin/users/" + id).with(ROOT)).andExpect(status().isOk())
				.andExpect(content().string(containsString("temporary — to be replaced")));
	}

	@Test
	void superAdminCannotLockThemselvesOut() throws Exception
	{
		long rootId = users.findByUsername("root").orElseThrow().id();
		mvc.perform(post("/admin/users/" + rootId).with(ROOT).with(csrf())
						.param("displayName", "Root").param("role", "ADMIN").param("enabled", "true"))
				.andExpect(model().attribute("error", containsString("your own role")));
		mvc.perform(post("/admin/users/" + rootId).with(ROOT).with(csrf())
						.param("displayName", "Root").param("role", "SUPER_ADMIN").param("enabled", "false"))
				.andExpect(model().attribute("error", containsString("your own")));
		mvc.perform(post("/admin/users/" + rootId + "/password").with(ROOT).with(csrf())
						.param("password", "another-temp-pass").param("repeat", "another-temp-pass"))
				.andExpect(redirectedUrl("/admin/users/" + rootId))
				.andExpect(flash().attribute("flashError", containsString("Password page")));
		// A super admin may promote an admin, and a changed role ends that admin's session
		long finId = users.findByUsername("fin").orElseThrow().id();
		MvcResult login = mvc.perform(formLogin("/login").user("fin").password(PASSWORD)).andReturn();
		MockHttpSession finSession = (MockHttpSession) login.getRequest().getSession(false);
		mvc.perform(post("/admin/users/" + finId).with(ROOT).with(csrf())
						.param("displayName", "Finance").param("role", "SUPER_ADMIN").param("enabled", "true"))
				.andExpect(redirectedUrl("/admin"));
		mvc.perform(get("/2fa/setup").session(finSession)).andExpect(status().isOk()); // the pending login is untouched
		assertThat(users.findByUsername("fin").orElseThrow().role()).isEqualTo(Role.SUPER_ADMIN);
		// Now root can step down, since fin is a super admin too; then fin is the last one
		mvc.perform(post("/admin/users/" + rootId).with(user("fin").roles("SUPER_ADMIN")).with(csrf())
						.param("displayName", "Root").param("role", "ADMIN").param("enabled", "true"))
				.andExpect(redirectedUrl("/admin"));
		mvc.perform(post("/admin/users/" + finId).with(user("fin").roles("SUPER_ADMIN")).with(csrf())
						.param("displayName", "Finance").param("role", "SUPER_ADMIN").param("enabled", "false"))
				.andExpect(model().attribute("error", containsString("your own")));
		// Put things back for the other tests
		users.updateProfile(rootId, "Root", Role.SUPER_ADMIN, true);
		users.updateProfile(finId, "Finance", Role.ADMIN, true);
	}

	/* ======================= administration: categories & settings ======================= */

	@Test
	void superAdminManagesCategoriesAndTheYearMode() throws Exception
	{
		mvc.perform(post("/admin/categories/new").with(ROOT).with(csrf())
						.param("name", "Sponsoring").param("heading", "OTHER").param("sortOrder", "55"))
				.andExpect(redirectedUrl("/admin#categories"));
		long id = jdbc.queryForObject("SELECT id FROM expense_option WHERE kind = 'CATEGORY' AND name = 'Sponsoring'", Long.class);
		mvc.perform(get("/expenses/new").with(DESK)).andExpect(content().string(containsString("Sponsoring")));

		mvc.perform(post("/admin/categories/" + id).with(ROOT).with(csrf())
						.param("heading", "PURCHASES").param("adminOnly", "true").param("active", "true").param("sortOrder", "56"))
				.andExpect(redirectedUrl("/admin#categories"));
		mvc.perform(get("/expenses/new").with(DESK)).andExpect(content().string(not(containsString("Sponsoring"))));
		mvc.perform(get("/expenses/new").with(FIN)).andExpect(content().string(containsString("Sponsoring")));
		assertThat(jdbc.queryForObject("SELECT heading FROM expense_option WHERE id = ?", String.class, id)).isEqualTo("PURCHASES");

		mvc.perform(post("/admin/categories/new").with(ROOT).with(csrf()).param("name", "sponsoring").param("heading", "OTHER"))
				.andExpect(flash().attribute("flashError", containsString("already a category")));
		long salaries = jdbc.queryForObject("SELECT id FROM expense_option WHERE kind = 'CATEGORY' AND name = 'Salaries'", Long.class);
		mvc.perform(post("/admin/categories/" + salaries).with(ROOT).with(csrf())
						.param("heading", "SALARIES").param("adminOnly", "true").param("active", "false"))
				.andExpect(flash().attribute("flashError", containsString("payroll")));
		mvc.perform(post("/admin/categories/new").with(FIN).with(csrf()).param("name", "Nope").param("heading", "OTHER"))
				.andExpect(status().isForbidden());

		assertThat(finance.resetEachYear()).isFalse();
		mvc.perform(post("/admin/settings/year-mode").with(ROOT).with(csrf()).param("mode", "RESET"))
				.andExpect(redirectedUrl("/admin#settings"));
		assertThat(finance.resetEachYear()).isTrue();
		mvc.perform(get("/reserves").with(FIN)).andExpect(content().string(containsString("since 1 Jan")));
		mvc.perform(post("/admin/settings/year-mode").with(ROOT).with(csrf()).param("mode", "CARRY"));
		assertThat(finance.resetEachYear()).isFalse();
	}

	/* ======================= administration: audit log ======================= */

	@Test
	void auditLogViewerFiltersEntries() throws Exception
	{
		mvc.perform(post("/admin/settings/year-mode").with(ROOT).with(csrf()).param("mode", "CARRY"));
		mvc.perform(get("/admin/audit").with(ROOT))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("SETTING_CHANGE")));
		mvc.perform(get("/admin/audit").with(ROOT).param("action", "SETTING_CHANGE").param("user", "root")
						.param("from", TODAY.toString()).param("to", TODAY.toString()).param("q", "year_mode"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("storage.year_mode")));
		mvc.perform(get("/admin/audit").with(ROOT).param("action", "NO_SUCH_ACTION"))
				.andExpect(content().string(containsString("No entries match")));
		mvc.perform(get("/admin/audit").with(ROOT).param("from", "garbage").param("page", "999")).andExpect(status().isOk());
		mvc.perform(get("/admin/audit").with(FIN)).andExpect(status().isForbidden());
		mvc.perform(get("/admin/audit").with(DESK)).andExpect(status().isForbidden());
	}
}
