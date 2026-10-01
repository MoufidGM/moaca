package com.cslsm.web;

import com.cslsm.web.activities.ActivityMath;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Staff splits and payroll, allocation keys, the restaurant manager and the restaurant till. */
@SpringBootTest
@AutoConfigureMockMvc
class StaffRestaurantFlowTest
{
	private static final LocalDate TODAY = LocalDate.now();
	private static final YearMonth MONTH = YearMonth.from(TODAY);

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry)
	{
		registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("staff.db") + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=true");
		registry.add("cslsm.security.secret-key", () -> "test-secret-key-that-is-long-enough-for-tests");
		registry.add("cslsm.storage.dir", () -> tempDir.resolve("files").toString());
	}

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired JdbcTemplate jdbc;
	@Autowired FinanceRepository finance;

	private static final RequestPostProcessor FIN = user("fin").roles("ADMIN");
	private static final RequestPostProcessor CHEF = user("chef").roles("RESTAURANT_MANAGER");
	private static final RequestPostProcessor DESK = user("desk").roles("RECEPTIONIST");

	@BeforeEach
	void accounts()
	{
		for (String[] u : new String[][]{{"fin", "ADMIN"}, {"chef", "RESTAURANT_MANAGER"}, {"desk", "RECEPTIONIST"}})
		{
			if (users.findByUsername(u[0]).isEmpty())
			{
				users.create(u[0], u[0], passwordEncoder.encode("a-long-enough-password"), Role.valueOf(u[1]));
			}
		}
	}

	private ActivityMath.Result overview() throws Exception
	{
		return (ActivityMath.Result) mvc.perform(get("/activities").with(FIN).param("period", "month").param("spread", "NONE"))
				.andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("result");
	}

	@Test
	void payrollSplitsSalariesLikeTheEmployee() throws Exception
	{
		mvc.perform(post("/employees/new").with(FIN).with(csrf())
						.param("name", "Coach Reda").param("job", "Coach").param("salary", "4 000")
						.param("splitActivity", "Académie", "", "", "").param("splitPercent", "100", "", "", ""))
				.andExpect(redirectedUrl("/employees"));
		mvc.perform(post("/employees/new").with(FIN).with(csrf())
						.param("name", "Agent sécurité").param("salary", "3000")
						.param("splitActivity", "Location de terrains", "Gym", "", "").param("splitPercent", "70", "30", "", ""))
				.andExpect(redirectedUrl("/employees"));
		mvc.perform(post("/employees/new").with(FIN).with(csrf())
						.param("name", "Bad split").param("splitActivity", "Gym", "Padel", "", "").param("splitPercent", "60", "60", "", ""))
				.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee", Integer.class)).isEqualTo(2);

		List<Long> ids = jdbc.queryForList("SELECT id FROM employee ORDER BY id", Long.class);
		mvc.perform(post("/payroll").with(FIN).with(csrf()).param("month", MONTH.toString())
						.param("employeeId", String.valueOf(ids.get(0)), String.valueOf(ids.get(1)))
						.param("amount", "4000", "3000").param("paidFrom", "BANK"))
				.andExpect(flash().attribute("flashOk", containsString("2 salary expense")));

		Map<String, Object> guard = jdbc.queryForMap("SELECT * FROM expense WHERE description LIKE '%Agent sécurité%'");
		assertThat(guard.get("category")).isEqualTo("Salaries");
		assertThat(guard.get("activity")).isEqualTo("Location de terrains");
		assertThat(guard.get("status")).isEqualTo("APPROVED");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_allocation WHERE expense_id = ?", Integer.class, guard.get("id"))).isEqualTo(2);

		// Same month again is refused; nothing doubled
		mvc.perform(post("/payroll").with(FIN).with(csrf()).param("month", MONTH.toString())
						.param("employeeId", String.valueOf(ids.get(0))).param("amount", "4000").param("paidFrom", "BANK"))
				.andExpect(flash().attribute("flashError", containsString("already has a salary")));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense WHERE category = 'Salaries'", Integer.class)).isEqualTo(2);

		ActivityMath.Result r = overview();
		assertThat(r.row("Académie").heading(ActivityMath.Heading.DIRECT_SALARIES)).isCloseTo(4000, within(0.01));
		assertThat(r.row("Location de terrains").heading(ActivityMath.Heading.SHARED_SALARIES)).isCloseTo(2100, within(0.01));
		assertThat(r.row("Gym").heading(ActivityMath.Heading.SHARED_SALARIES)).isCloseTo(900, within(0.01));

		mvc.perform(get("/employees").with(DESK)).andExpect(status().isForbidden());
		mvc.perform(get("/payroll").with(FIN)).andExpect(status().isOk());
	}

	@Test
	void utilitiesFollowTheKey() throws Exception
	{
		mvc.perform(post("/activities/settings/key").with(FIN).with(csrf()).param("kind", "UTILITIES")
						.param("keyActivity", "Location de terrains", "Gym").param("keyPercent", "50", "50"))
				.andExpect(flash().attribute("flashOk", containsString("Key saved")));
		mvc.perform(post("/activities/settings/key").with(FIN).with(csrf()).param("kind", "UTILITIES")
						.param("keyActivity", "Location de terrains", "Gym").param("keyPercent", "50", "40"))
				.andExpect(flash().attribute("flashError", containsString("add up to 90%")));
		mvc.perform(multipart("/expenses/new").with(FIN).with(csrf())
				.param("date", TODAY.toString()).param("category", "Electricity").param("activity", "General")
				.param("description", "Facture ONEE").param("amount", "1000").param("paidFrom", "BANK"));
		ActivityMath.Result r = overview();
		assertThat(r.isUtilitiesKeyUsed()).isTrue();
		assertThat(r.row("Location de terrains").heading(ActivityMath.Heading.UTILITIES)).isCloseTo(500, within(0.01));
		assertThat(r.row("Gym").heading(ActivityMath.Heading.UTILITIES)).isCloseTo(500, within(0.01));
	}

	@Test
	void restaurantManagerRecordsSalesAndExpensesInItsOwnTill() throws Exception
	{
		mvc.perform(get("/").with(CHEF)).andExpect(redirectedUrl("/restaurant"));
		mvc.perform(get("/restaurant").with(CHEF)).andExpect(status().isOk());
		mvc.perform(get("/today").with(CHEF)).andExpect(status().isForbidden());
		mvc.perform(get("/dashboard").with(CHEF)).andExpect(status().isForbidden());
		mvc.perform(get("/daily-logs").with(CHEF)).andExpect(status().isOk()); // for Tiki Taka's own files
		mvc.perform(get("/restaurant").with(DESK)).andExpect(status().isForbidden());

		mvc.perform(post("/restaurant/sales").with(CHEF).with(csrf())
						.param("date", TODAY.toString()).param("cash", "3 200").param("card", "1100").param("covers", "45"))
				.andExpect(flash().attribute("flashOk", containsString("Sales recorded")));
		mvc.perform(post("/restaurant/sales").with(CHEF).with(csrf())
						.param("date", TODAY.plusDays(1).toString()).param("cash", "10").param("card", "0"))
				.andExpect(flash().attribute("flashError", containsString("future")));
		mvc.perform(post("/restaurant/sales").with(CHEF).with(csrf())
						.param("date", TODAY.minusDays(20).toString()).param("cash", "10").param("card", "0"))
				.andExpect(flash().attribute("flashError", containsString("7 days")));
		// Re-entering a day replaces it
		mvc.perform(post("/restaurant/sales").with(CHEF).with(csrf())
				.param("date", TODAY.toString()).param("cash", "3300").param("card", "1100"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_sales", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT cash FROM restaurant_sales", Double.class)).isEqualTo(3300);

		// The manager's expense is always Tiki Taka, paid from the restaurant till, pending
		mvc.perform(multipart("/expenses/new").with(CHEF).with(csrf())
						.param("date", TODAY.toString()).param("category", "Food & drinks").param("activity", "Gym")
						.param("description", "Marché du jour").param("amount", "1500").param("paidFrom", "BANK"))
				.andExpect(status().is3xxRedirection());
		Map<String, Object> e = jdbc.queryForMap("SELECT * FROM expense WHERE description = 'Marché du jour'");
		assertThat(e.get("activity")).isEqualTo("Tiki Taka");
		assertThat(e.get("till")).isEqualTo("RESTAURANT");
		assertThat(e.get("paid_from")).isEqualTo("RECEPTION");
		assertThat(e.get("status")).isEqualTo("PENDING");

		assertThat(finance.restaurantBalance(TODAY).balance()).isCloseTo(3300 - 1500, within(0.01));
		assertThat(finance.receptionBalance(TODAY).expensesPaid()).isEqualTo(0);

		// Admin moves restaurant cash to the safe: leaves the restaurant till, not the reception
		double safeBefore = finance.safeBalance().balance();
		mvc.perform(post("/restaurant/movements").with(FIN).with(csrf())
						.param("action", "RESTAURANT_TO_SAFE").param("date", TODAY.toString()).param("amount", "1000"))
				.andExpect(flash().attribute("flashOk", "Recorded."));
		assertThat(finance.restaurantBalance(TODAY).balance()).isCloseTo(800, within(0.01));
		assertThat(finance.safeBalance().balance()).isCloseTo(safeBefore + 1000, within(0.01));
		assertThat(finance.receptionBalance(TODAY).movedToSafe()).isEqualTo(0);
		mvc.perform(post("/restaurant/movements").with(CHEF).with(csrf())
						.param("action", "RESTAURANT_TO_SAFE").param("date", TODAY.toString()).param("amount", "10"))
				.andExpect(status().isForbidden());

		// Tiki Taka appears in the analysis with its sales as revenue
		ActivityMath.Result r = overview();
		assertThat(r.row("Tiki Taka").revenue).isCloseTo(4400, within(0.01));
		assertThat(r.row("Tiki Taka").heading(ActivityMath.Heading.PURCHASES)).isCloseTo(1500, within(0.01));
		mvc.perform(get("/activities/detail").with(FIN).param("name", "Tiki Taka").param("period", "month")).andExpect(status().isOk());
		mvc.perform(get("/reserves").with(FIN)).andExpect(status().isOk());
	}
}
