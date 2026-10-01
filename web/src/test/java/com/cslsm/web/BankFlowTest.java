package com.cslsm.web;

import com.cslsm.web.finance.FinanceModels.BankAccount;
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
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two bank accounts: what feeds each one, expenses paid from each, the admin's bank
 * movements (other money in, payments, transfers, statement corrections) and the pages.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BankFlowTest
{
	private static final LocalDate TODAY = LocalDate.now();

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry)
	{
		registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("bank.db") + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=true");
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

	@BeforeEach
	void accounts()
	{
		for (String[] u : new String[][]{{"fin", "ADMIN"}, {"desk", "RECEPTIONIST"}})
		{
			if (users.findByUsername(u[0]).isEmpty())
			{
				users.create(u[0], u[0], passwordEncoder.encode("a-long-enough-password"), Role.valueOf(u[1]));
			}
		}
	}

	@Test
	void eachAccountIsFedByItsOwnSideAndCorrectedToTheStatement() throws Exception
	{
		String d = TODAY.minusDays(1).toString();
		// Daily log: 500 card + 100 cheque -> Association. Restaurant: 200 card -> Tiki Taka. Salon: 80 card -> Association.
		jdbc.update("INSERT INTO daily_summary (log_date, total_terrain, total_ttc, total_cash, total_card, total_cheque, drinks_amount_total)"
				+ " VALUES (?, 2000, 2000, 1400, 500, 100, 0)", d);
		jdbc.update("INSERT INTO restaurant_sales (sale_date, cash, card, entered_by, updated_at) VALUES (?, 300, 200, 'chef', 'x')", d);
		jdbc.update("INSERT INTO salon_sales (sale_date, cash, card, entered_by, updated_at) VALUES (?, 50, 80, 'desk', 'x')", d);
		// Cash taken to the bank: reception 1 000 and safe 400 -> Association; restaurant till 300 -> Tiki Taka
		mvc.perform(post("/reserves/movements").with(FIN).with(csrf()).param("action", "RECEPTION_TO_BANK").param("date", d).param("amount", "1000"));
		mvc.perform(post("/reserves/movements").with(FIN).with(csrf()).param("action", "SAFE_TO_BANK").param("date", d).param("amount", "400"));
		mvc.perform(post("/restaurant/movements").with(FIN).with(csrf()).param("action", "RESTAURANT_TO_BANK").param("date", d).param("amount", "300"));
		// Expenses paid from each account
		mvc.perform(multipart("/expenses/new").with(FIN).with(csrf()).param("date", d).param("category", "Electricity").param("activity", "General")
				.param("description", "Facture ONEE").param("amount", "150").param("paidFrom", "BANK")).andExpect(status().is3xxRedirection());
		mvc.perform(multipart("/expenses/new").with(FIN).with(csrf()).param("date", d).param("category", "Food & drinks").param("activity", "Tiki Taka")
				.param("description", "Grossiste").param("amount", "50").param("paidFrom", "RESTAURANT_BANK")).andExpect(status().is3xxRedirection());
		assertThat(jdbc.queryForMap("SELECT paid_from, till FROM expense WHERE description = 'Grossiste'"))
				.containsEntry("paid_from", "BANK").containsEntry("till", "RESTAURANT");

		assertThat(finance.bankBalance(BankAccount.ASSOCIATION).balance()).isCloseTo(600 + 80 + 1000 + 400 - 150, within(0.005));
		assertThat(finance.bankBalance(BankAccount.RESTAURANT).balance()).isCloseTo(200 + 300 - 50, within(0.005));
		// The restaurant's till is untouched by its bank expense; the reception's by the Association's
		assertThat(finance.restaurantBalance(TODAY).balance()).isCloseTo(300 - 300, within(0.005));
		assertThat(finance.receptionBalance(TODAY).expensesPaid()).isZero();

		// Admin bank movements: other money in, a transfer, a statement correction, a delete
		mvc.perform(post("/reserves/bank").with(FIN).with(csrf()).param("account", "RESTAURANT").param("action", "IN")
						.param("date", d).param("amount", "100").param("note", "refund"))
				.andExpect(redirectedUrl("/reserves#bank"));
		mvc.perform(post("/reserves/bank").with(FIN).with(csrf()).param("account", "RESTAURANT").param("action", "TRANSFER")
						.param("date", d).param("amount", "120"))
				.andExpect(flash().attribute("flashOk", "Recorded."));
		assertThat(finance.bankBalance(BankAccount.RESTAURANT).balance()).isCloseTo(450 + 100 - 120, within(0.005));
		assertThat(finance.bankBalance(BankAccount.ASSOCIATION).balance()).isCloseTo(1930 + 120, within(0.005));
		mvc.perform(post("/reserves/bank/statement").with(FIN).with(csrf()).param("account", "ASSOCIATION").param("balance", "2 500"))
				.andExpect(flash().attribute("flashOk", containsString("statement")));
		assertThat(finance.bankBalance(BankAccount.ASSOCIATION).balance()).isCloseTo(2500, within(0.005));
		mvc.perform(post("/reserves/bank/statement").with(FIN).with(csrf()).param("account", "ASSOCIATION").param("balance", "2500"))
				.andExpect(flash().attribute("flashInfo", containsString("Nothing recorded")));
		long refund = jdbc.queryForObject("SELECT id FROM bank_movement WHERE note = 'refund'", Long.class);
		mvc.perform(post("/reserves/bank/" + refund + "/delete").with(FIN).with(csrf())).andExpect(redirectedUrl("/reserves#bank"));
		assertThat(finance.bankBalance(BankAccount.RESTAURANT).balance()).isCloseTo(330, within(0.005));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action LIKE 'BANK_%'", Integer.class)).isEqualTo(4);
		// Paying out more than the account holds is recorded with a warning
		mvc.perform(post("/reserves/bank").with(FIN).with(csrf()).param("account", "RESTAURANT").param("action", "OUT")
						.param("date", d).param("amount", "5000").param("note", "fees"))
				.andExpect(flash().attribute("flashError", containsString("negative")));

		// Pages
		mvc.perform(get("/reserves").with(FIN)).andExpect(status().isOk())
				.andExpect(content().string(containsString("Association bank account")))
				.andExpect(content().string(containsString("Tiki Taka bank account")));
		mvc.perform(get("/dashboard").with(FIN)).andExpect(status().isOk()).andExpect(content().string(containsString("Tiki Taka account")));
		mvc.perform(get("/restaurant").with(FIN)).andExpect(status().isOk()).andExpect(content().string(containsString("Tiki Taka bank account")));
		mvc.perform(get("/expenses").with(FIN).param("paidFrom", "RESTAURANT_BANK").param("month", d.substring(0, 7))).andExpect(status().isOk())
				.andExpect(content().string(containsString("Grossiste")));
		mvc.perform(get("/expenses/new").with(FIN)).andExpect(content().string(containsString("Tiki Taka bank account")));
		mvc.perform(post("/reserves/bank").with(DESK).with(csrf()).param("account", "ASSOCIATION").param("action", "IN")
				.param("date", d).param("amount", "1")).andExpect(status().isForbidden());
	}
}
