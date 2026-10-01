package com.cslsm.web;

import com.cslsm.web.activities.ActivityMath;
import com.cslsm.web.security.Role;
import com.cslsm.web.security.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drinks counted as income, expense splits, the per-activity analysis, and activity settings.
 * Uses its own month (January 2026) so the figures are exact.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityFlowTest
{
	private static final LocalDate DAY = LocalDate.of(2026, 1, 10);
	private static final String FROM = "2026-01-01";
	private static final String TO = "2026-01-31";

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry)
	{
		registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("activities.db") + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=true");
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

	private static final RequestPostProcessor FIN = user("fin").roles("ADMIN");
	private static final RequestPostProcessor DESK = user("desk").roles("RECEPTIONIST");

	private static boolean seeded;

	@BeforeEach
	void seed() throws Exception
	{
		if (users.findByUsername("fin").isEmpty())
		{
			users.create("fin", "Finance", passwordEncoder.encode("a-long-enough-password"), Role.ADMIN);
			users.create("desk", "Desk", passwordEncoder.encode("a-long-enough-password"), Role.RECEPTIONIST);
		}
		if (seeded)
		{
			return;
		}
		// Revenue: Terrain 30 000, Gym 10 000, drinks 500 → center income 40 500
		jdbc.update("INSERT INTO daily_summary (log_date, total_terrain, total_gym, total_ttc, total_cash, total_card, total_cheque, drinks_amount_total)"
				+ " VALUES (?, 30000, 10000, 40000, 40000, 0, 0, 500)", DAY.toString());
		expense("Location de terrains", "Filets terrain", "2000");
		expense("General", "Produits entretien général", "4000"); // shared
		expense("Events", "Sono tournoi", "700");                    // own line
		jdbc.update("INSERT INTO expense (expense_date, category, description, amount, activity, paid_from_storage)"
				+ " VALUES (?, 'Supplies', 'Old spelling', 300, 'T-Foot', 1)", DAY.toString());
		seeded = true;
	}

	private void expense(String activity, String description, String amount) throws Exception
	{
		mvc.perform(multipart("/expenses/new").with(FIN).with(csrf())
				.param("date", DAY.toString()).param("category", "Supplies").param("activity", activity)
				.param("description", description).param("amount", amount).param("paidFrom", "RECEPTION"));
	}

	private ActivityMath.Result overview(String spread) throws Exception
	{
		return (ActivityMath.Result) mvc.perform(get("/activities").with(FIN)
						.param("period", "custom").param("from", FROM).param("to", TO).param("spread", spread))
				.andExpect(status().isOk())
				.andReturn().getModelAndView().getModel().get("result");
	}

	@Test
	void drinksCountAsIncome() throws Exception
	{
		ActivityMath.Result r = overview("REVENUE");
		assertThat(r.totalRevenue).isEqualTo(40_500);
		assertThat(r.row("Drinks").revenue).isEqualTo(500);
	}

	@Test
	void splitExpenseLandsOnEachActivity() throws Exception
	{
		mvc.perform(multipart("/expenses/new").with(FIN).with(csrf())
						.param("date", DAY.toString()).param("category", "Electricity").param("activity", "General")
						.param("description", "Facture électricité split").param("amount", "1000").param("paidFrom", "BANK")
						.param("splitActivity", "Location de terrains", "Gym", "", "").param("splitPercent", "60", "40", "", ""))
				.andExpect(status().is3xxRedirection());
		long id = jdbc.queryForObject("SELECT id FROM expense WHERE description = 'Facture électricité split'", Long.class);
		assertThat(jdbc.queryForObject("SELECT activity FROM expense WHERE id = ?", String.class, id)).isEqualTo("Location de terrains");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_allocation WHERE expense_id = ?", Integer.class, id)).isEqualTo(2);

		ActivityMath.Result r = overview("NONE");
		assertThat(r.row("Gym").directCosts).isEqualTo(400);
		assertThat(r.row("Location de terrains").directCosts).isGreaterThanOrEqualTo(2_600);

		// Bad splits are refused with a clear message
		mvc.perform(multipart("/expenses/new").with(FIN).with(csrf())
						.param("date", DAY.toString()).param("category", "Electricity").param("activity", "General")
						.param("description", "Split qui ne tombe pas juste").param("amount", "100")
						.param("splitActivity", "Location de terrains", "Gym", "", "").param("splitPercent", "60", "30", "", ""))
				.andExpect(model().attribute("error", containsString("add up to 90%")));
		mvc.perform(multipart("/expenses/new").with(FIN).with(csrf())
						.param("date", DAY.toString()).param("category", "Electricity").param("activity", "General")
						.param("description", "Split en double").param("amount", "100")
						.param("splitActivity", "Location de terrains", "location de terrains", "", "").param("splitPercent", "50", "50", "", ""))
				.andExpect(model().attribute("error", containsString("appears twice")));
	}

	@Test
	void everySpreadAddsUpToIncomeMinusExpenses() throws Exception
	{
		double income = 40_500;
		double expenses = jdbc.queryForObject("SELECT SUM(amount) FROM expense WHERE status <> 'REJECTED' AND expense_date BETWEEN ? AND ?",
				Double.class, FROM, TO);
		for (String spread : new String[]{"REVENUE", "EQUAL", "NONE"})
		{
			ActivityMath.Result r = overview(spread);
			double rows = r.revenueRows.stream().mapToDouble(ActivityMath.Row::getProfit).sum()
					+ r.otherRows.stream().mapToDouble(ActivityMath.Row::getProfit).sum();
			assertThat(rows).as(spread).isCloseTo(income - expenses, within(0.01));
		}
	}

	@Test
	void settingsMergeOldSpellingsAndChangeRules() throws Exception
	{
		assertThat(overview("REVENUE").unassignedTotal).isGreaterThanOrEqualTo(300);

		mvc.perform(post("/activities/settings/merge").with(FIN).with(csrf()).param("from", "T-Foot").param("to", "Location de terrains"))
				.andExpect(flash().attribute("flashOk", containsString("moved to Location de terrains")));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense WHERE activity = 'T-Foot'", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ACTIVITY_MERGE'", Integer.class)).isEqualTo(1);

		long eventsId = jdbc.queryForObject("SELECT id FROM expense_option WHERE kind = 'ACTIVITY' AND name = 'Events'", Long.class);
		mvc.perform(post("/activities/settings/" + eventsId).with(FIN).with(csrf())
				.param("rule", "PART_OF").param("partOf", "Location de terrains").param("active", "true"));
		ActivityMath.Result r = overview("NONE");
		assertThat(r.row("Events")).isNull();
		assertThat(r.row("Location de terrains").directCosts).isGreaterThanOrEqualTo(2_000 + 700 + 300);

		mvc.perform(post("/activities/settings/" + eventsId).with(FIN).with(csrf())
						.param("rule", "PART_OF").param("partOf", "Events").param("active", "true"))
				.andExpect(flash().attribute("flashError", containsString("earns revenue")));
		mvc.perform(post("/activities/settings/new").with(FIN).with(csrf()).param("name", "G3K").param("rule", "SEPARATE"))
				.andExpect(flash().attribute("flashOk", "Activity added."));
		mvc.perform(post("/activities/settings/new").with(FIN).with(csrf()).param("name", "g3k").param("rule", "SEPARATE"))
				.andExpect(flash().attribute("flashError", containsString("already exists")));
	}

	@Test
	void pagesRenderForAdminsOnly() throws Exception
	{
		for (String name : new String[]{"Location de terrains", "General", "Events", "Danse", "Salon", "Drinks"})
		{
			mvc.perform(get("/activities/detail").with(FIN).param("name", name).param("period", "custom")
					.param("from", FROM).param("to", TO)).andExpect(status().isOk());
		}
		mvc.perform(get("/activities/detail").with(FIN).param("name", "Nope")).andExpect(status().isNotFound());
		mvc.perform(get("/activities/settings").with(FIN)).andExpect(status().isOk());
		mvc.perform(get("/dashboard").with(FIN)).andExpect(status().isOk());
		mvc.perform(get("/expenses/new").with(FIN)).andExpect(status().isOk());

		mvc.perform(get("/activities").with(DESK)).andExpect(status().isForbidden());
		mvc.perform(get("/activities/settings").with(DESK)).andExpect(status().isForbidden());
		mvc.perform(post("/activities/settings/merge").with(DESK).with(csrf()).param("from", "x").param("to", "Location de terrains"))
				.andExpect(status().isForbidden());
	}
}
