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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Salon: the reception records its daily sales; the cash lands in the reception till; the
 * sales are the Salon's revenue in the analysis, the income pages and the reports.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SalonFlowTest
{
	private static final LocalDate TODAY = LocalDate.now();

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry)
	{
		registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("salon.db") + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=true");
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

	private static final RequestPostProcessor FIN = user("fin").roles("ADMIN");
	private static final RequestPostProcessor DESK = user("desk").roles("RECEPTIONIST");
	private static final RequestPostProcessor CHEF = user("chef").roles("RESTAURANT_MANAGER");

	@BeforeEach
	void accounts()
	{
		for (String[] u : new String[][]{{"fin", "ADMIN"}, {"desk", "RECEPTIONIST"}, {"chef", "RESTAURANT_MANAGER"}})
		{
			if (users.findByUsername(u[0]).isEmpty())
			{
				users.create(u[0], u[0], passwordEncoder.encode("a-long-enough-password"), Role.valueOf(u[1]));
			}
		}
	}

	@Test
	void receptionRecordsSalesThatCountAsSalonRevenueAndReceptionCash() throws Exception
	{
		double receptionBefore = finance.receptionBalance(TODAY).balance();

		mvc.perform(get("/salon").with(FIN)).andExpect(status().isOk()).andExpect(content().string(containsString("Sales of the day")));
		mvc.perform(get("/salon").with(DESK)).andExpect(status().isForbidden());
		mvc.perform(get("/salon").with(CHEF)).andExpect(status().isForbidden());

		mvc.perform(post("/salon/sales").with(FIN).with(csrf())
						.param("date", TODAY.toString()).param("cash", "1 200").param("card", "300").param("clients", "9"))
				.andExpect(flash().attribute("flashOk", containsString("recorded")));
		mvc.perform(post("/salon/sales").with(FIN).with(csrf())
						.param("date", TODAY.plusDays(1).toString()).param("cash", "10").param("card", "0"))
				.andExpect(flash().attribute("flashError", containsString("future")));
		mvc.perform(post("/salon/sales").with(DESK).with(csrf())
						.param("date", TODAY.minusDays(20).toString()).param("cash", "10").param("card", "0"))
				.andExpect(status().isForbidden());
		mvc.perform(post("/salon/sales").with(FIN).with(csrf())
						.param("date", TODAY.minusDays(20).toString()).param("cash", "500").param("card", "0"))
				.andExpect(flash().attribute("flashOk", containsString("recorded")));
		// Re-entering a day replaces it
		mvc.perform(post("/salon/sales").with(FIN).with(csrf()).param("date", TODAY.toString()).param("cash", "1000").param("card", "300"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM salon_sales", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT cash FROM salon_sales WHERE sale_date = ?", Double.class, TODAY.toString())).isEqualTo(1000);

		// Cash to the reception till, card not; both are the center's income
		assertThat(finance.receptionBalance(TODAY).balance()).isEqualTo(receptionBefore + 1000 + 500);
		assertThat(finance.income(TODAY.minusDays(30), TODAY)).isEqualTo(1300 + 500);
		assertThat(finance.incomeByActivity(TODAY, TODAY)).anyMatch(a -> a.name().equals("Salon") && a.amount() == 1300);
		assertThat(finance.paymentMix(TODAY, TODAY)).anyMatch(a -> a.name().equals("Salon card") && a.amount() == 300);

		// A Salon expense is a direct cost of a revenue activity now
		mvc.perform(multipart("/expenses/new").with(FIN).with(csrf())
						.param("date", TODAY.toString()).param("category", "Supplies").param("activity", "Salon")
						.param("description", "Produits salon").param("amount", "400").param("paidFrom", "RECEPTION"))
				.andExpect(status().is3xxRedirection());
		ActivityMath.Result r = (ActivityMath.Result) mvc.perform(get("/activities").with(FIN).param("period", "custom")
						.param("from", TODAY.minusDays(30).toString()).param("to", TODAY.toString()))
				.andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("result");
		assertThat(r.row("Salon").revenue).isEqualTo(1800);
		assertThat(r.row("Salon").directCosts).isEqualTo(400);
		assertThat(r.row("Salon").isRevenueActivity()).isTrue();

		// Admin month view, delete, and the other pages that show the Salon
		mvc.perform(get("/salon").with(FIN).param("month", TODAY.toString().substring(0, 7)))
				.andExpect(status().isOk()).andExpect(content().string(containsString("Salon expenses")));
		mvc.perform(post("/salon/sales/delete").with(DESK).with(csrf()).param("date", TODAY.toString())).andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action LIKE 'SALON_SALES%' AND username = 'fin'", Integer.class)).isEqualTo(3);
		mvc.perform(post("/salon/sales/delete").with(FIN).with(csrf()).param("date", TODAY.minusDays(20).toString()))
				.andExpect(flash().attribute("flashOk", containsString("deleted")));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM salon_sales", Integer.class)).isEqualTo(1);
		mvc.perform(get("/income").with(FIN).param("unit", "day").param("date", TODAY.toString()))
				.andExpect(status().isOk()).andExpect(content().string(containsString("Salon")));
		mvc.perform(get("/reports").with(FIN).param("report", "pnl")).andExpect(status().isOk())
				.andExpect(content().string(containsString(">Salon<")));
		mvc.perform(get("/reserves").with(FIN)).andExpect(status().isOk()).andExpect(content().string(containsString("Salon cash sales")));
		mvc.perform(get("/activities/detail").with(FIN).param("name", "Salon").param("period", "month")).andExpect(status().isOk());
	}
}
