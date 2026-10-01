package com.cslsm.web;

import com.cslsm.web.expenses.ExpenseImportService;
import com.cslsm.web.finance.FinanceRepository;
import com.cslsm.web.security.Role;
import com.cslsm.web.security.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 2 end to end: expenses and their approval, receipts, the bank-deposit fix,
 * daily-log uploads, cash movements, and every page rendering for each role.
 */
@SpringBootTest
@AutoConfigureMockMvc
class M2FlowTest
{
	private static final LocalDate TODAY = LocalDate.now();
	private static final DateTimeFormatter DL = DateTimeFormatter.ofPattern("dd-MM-yyyy");
	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry)
	{
		registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("m2.db") + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=true");
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

	private static final RequestPostProcessor DESK = user("desk").roles("RECEPTIONIST");
	private static final RequestPostProcessor DESK2 = user("desk2").roles("RECEPTIONIST");
	private static final RequestPostProcessor FIN = user("fin").roles("ADMIN");

	@BeforeEach
	void accounts()
	{
		create("desk", "Desk One", Role.RECEPTIONIST);
		create("desk2", "Desk Two", Role.RECEPTIONIST);
		create("fin", "Finance", Role.ADMIN);
	}

	private void create(String username, String name, Role role)
	{
		if (users.findByUsername(username).isEmpty())
		{
			users.create(username, name, passwordEncoder.encode("a-long-enough-password"), role);
		}
	}

	private long expenseId(String description)
	{
		return jdbc.queryForObject("SELECT id FROM expense WHERE description = ?", Long.class, description);
	}

	private Map<String, Object> expense(String description)
	{
		return jdbc.queryForMap("SELECT * FROM expense WHERE description = ?", description);
	}

	/* ======================= expenses: receptionist ======================= */

	@Test
	void receptionistEntryIsPendingAndAlwaysFromTheReception() throws Exception
	{
		mvc.perform(multipart("/expenses/new").with(DESK).with(csrf())
						.param("date", TODAY.toString()).param("category", "Supplies").param("activity", "Padel")
						.param("description", "Balles de padel").param("amount", "350").param("paymentMethod", "CASH")
						.param("paidFrom", "BANK"))
				.andExpect(redirectedUrlPattern("/expenses/*"));
		Map<String, Object> row = expense("Balles de padel");
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("paid_from")).isEqualTo("RECEPTION");
		assertThat(row.get("entered_by")).isEqualTo("Desk One");
		assertThat(row.get("approved_by")).isNull();
	}

	@Test
	void receptionistRulesAreEnforced() throws Exception
	{
		mvc.perform(multipart("/expenses/new").with(DESK).with(csrf())
						.param("date", TODAY.toString()).param("category", "Salaries").param("activity", "General")
						.param("description", "Salaire").param("amount", "5000"))
				.andExpect(status().isOk())
				.andExpect(model().attribute("error", containsString("entered by an admin")));
		mvc.perform(multipart("/expenses/new").with(DESK).with(csrf())
						.param("date", TODAY.plusYears(2).toString()).param("category", "Supplies").param("activity", "General")
						.param("description", "Typo year").param("amount", "750"))
				.andExpect(model().attribute("error", containsString("cannot be in the future")));
		mvc.perform(multipart("/expenses/new").with(DESK).with(csrf())
						.param("date", TODAY.minusDays(8).toString()).param("category", "Supplies").param("activity", "General")
						.param("description", "Too old").param("amount", "10"))
				.andExpect(model().attribute("error", containsString("7 days back")));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense WHERE description IN ('Salaire', 'Typo year', 'Too old')", Integer.class)).isZero();
	}

	@Test
	void receptionistsOnlySeeTheirOwnEntries() throws Exception
	{
		mvc.perform(multipart("/expenses/new").with(DESK).with(csrf())
				.param("date", TODAY.toString()).param("category", "Transport").param("activity", "General")
				.param("description", "Taxi livraison").param("amount", "60"));
		long id = expenseId("Taxi livraison");
		mvc.perform(get("/expenses/" + id).with(DESK)).andExpect(status().isOk());
		mvc.perform(get("/expenses/" + id).with(DESK2)).andExpect(status().isNotFound());
		mvc.perform(post("/expenses/" + id + "/delete").with(DESK2).with(csrf())).andExpect(status().isNotFound());
		mvc.perform(post("/expenses/" + id + "/approve").with(DESK).with(csrf())).andExpect(status().isForbidden());
	}

	/* ======================= expenses: admin ======================= */

	@Test
	void adminApprovesOrRejectsWithAReason() throws Exception
	{
		mvc.perform(multipart("/expenses/new").with(DESK).with(csrf())
				.param("date", TODAY.toString()).param("category", "Maintenance").param("activity", "Gym")
				.param("description", "Réparation tapis").param("amount", "1 200"));
		mvc.perform(multipart("/expenses/new").with(DESK).with(csrf())
				.param("date", TODAY.toString()).param("category", "Other").param("activity", "General")
				.param("description", "Achat douteux").param("amount", "90"));
		long ok = expenseId("Réparation tapis");
		long bad = expenseId("Achat douteux");

		mvc.perform(post("/expenses/approve").with(FIN).with(csrf()).param("ids", String.valueOf(ok)))
				.andExpect(flash().attribute("flashOk", containsString("1 expense")));
		assertThat(expense("Réparation tapis").get("status")).isEqualTo("APPROVED");
		assertThat(expense("Réparation tapis").get("approved_by")).isEqualTo("Finance");

		mvc.perform(post("/expenses/" + bad + "/reject").with(FIN).with(csrf()).param("reason", " "))
				.andExpect(flash().attribute("flashError", containsString("reason")));
		mvc.perform(post("/expenses/" + bad + "/reject").with(FIN).with(csrf()).param("reason", "No receipt"))
				.andExpect(flash().attribute("flashOk", "Rejected."));
		assertThat(expense("Achat douteux").get("status")).isEqualTo("REJECTED");
		assertThat(expense("Achat douteux").get("rejection_reason")).isEqualTo("No receipt");
	}

	@Test
	void bankDepositFixLeavesTheBalanceAndRemovesTheExpense() throws Exception
	{
		mvc.perform(multipart("/expenses/new").with(FIN).with(csrf())
				.param("date", TODAY.minusDays(1).toString()).param("category", "Supplies").param("activity", "General")
				.param("description", "Versement au compte SMA").param("amount", "60000").param("paidFrom", "RECEPTION"));
		long id = expenseId("Versement au compte SMA");
		assertThat(expense("Versement au compte SMA").get("status")).isEqualTo("APPROVED");

		double balanceBefore = finance.receptionBalance(TODAY).balance();
		double expensesBefore = finance.expenses(TODAY.minusDays(1), TODAY);
		mvc.perform(post("/expenses/" + id + "/bank-deposit").with(FIN).with(csrf()))
				.andExpect(flash().attribute("flashOk", containsString("bank deposits")));

		assertThat(expense("Versement au compte SMA").get("status")).isEqualTo("REJECTED");
		assertThat(finance.receptionBalance(TODAY).balance()).isEqualTo(balanceBefore);
		assertThat(finance.expenses(TODAY.minusDays(1), TODAY)).isEqualTo(expensesBefore - 60000);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement WHERE type = 'BANK' AND amount = 60000", Integer.class)).isEqualTo(1);
	}

	@Test
	void receiptsAreCheckedByContent() throws Exception
	{
		mvc.perform(multipart("/expenses/new")
						.file(new MockMultipartFile("receipts", "ticket.png", "image/png", PNG))
						.with(DESK).with(csrf())
						.param("date", TODAY.toString()).param("category", "Supplies").param("activity", "General")
						.param("description", "Avec ticket").param("amount", "45"))
				.andExpect(redirectedUrlPattern("/expenses/*"));
		long id = expenseId("Avec ticket");
		assertThat(jdbc.queryForObject("SELECT content_type FROM expense_attachment WHERE expense_id = ?", String.class, id)).isEqualTo("image/png");
		long attachmentId = jdbc.queryForObject("SELECT id FROM expense_attachment WHERE expense_id = ?", Long.class, id);
		mvc.perform(get("/expenses/receipts/" + attachmentId).with(DESK)).andExpect(status().isOk());
		mvc.perform(get("/expenses/receipts/" + attachmentId).with(DESK2)).andExpect(status().isNotFound());

		mvc.perform(multipart("/expenses/new")
						.file(new MockMultipartFile("receipts", "photo.jpg", "image/jpeg", "<html><script>alert(1)</script></html>".getBytes()))
						.with(DESK).with(csrf())
						.param("date", TODAY.toString()).param("category", "Supplies").param("activity", "General")
						.param("description", "Fake receipt").param("amount", "45"))
				.andExpect(model().attribute("error", containsString("not a photo or a PDF")));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense WHERE description = 'Fake receipt'", Integer.class)).isZero();
	}

	@Test
	void expenseSheetImportPreviewsThenSavesOnlyValidRows() throws Exception
	{
		byte[] sheet = TestWorkbooks.expenseSheet(
				new Object[]{TODAY.minusDays(3).format(DL), "Supplies", "Import ok", 20, "Cash", null, null, "Menage", "yes"},
				new Object[]{TODAY.plusYears(1).format(DL), "Salaries", "Import future", 750, "Cash", null, null, "General", "yes"},
				new Object[]{TODAY.minusDays(3).format(DL), "Nonsense", "Import bad category", 10, null, null, null, null, null});
		MvcResult preview = mvc.perform(multipart("/expenses/import")
						.file(new MockMultipartFile("file", "juin.xlsx", "application/octet-stream", sheet))
						.with(FIN).with(csrf()))
				.andExpect(status().isOk())
				.andReturn();
		ExpenseImportService.Preview p = (ExpenseImportService.Preview) preview.getModelAndView().getModel().get("preview");
		assertThat(p.okCount()).isEqualTo(1);
		assertThat(p.errorCount()).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense WHERE description LIKE 'Import %'", Integer.class)).isZero();

		mvc.perform(post("/expenses/import/confirm").session((MockHttpSession) preview.getRequest().getSession())
						.with(FIN).with(csrf()).param("token", p.token()))
				.andExpect(flash().attribute("flashOk", containsString("1 expense(s) imported")));
		Map<String, Object> row = expense("Import ok");
		assertThat(row.get("status")).isEqualTo("APPROVED");
		assertThat(row.get("activity")).isEqualTo("Cleaning");

		mvc.perform(get("/expenses/import").with(DESK)).andExpect(status().isOk());
	}

	/* ======================= daily logs ======================= */

	private MockMultipartFile dailyLog(LocalDate date, byte[] content)
	{
		return new MockMultipartFile("files", "DL-" + date.format(DL) + ".xlsx", "application/octet-stream", content);
	}

	@Test
	void dailyLogUploadImportsOnceThenOnlyAnAdminCanReplace() throws Exception
	{
		LocalDate day = TODAY.minusDays(2);
		byte[] v1 = TestWorkbooks.dailyLog(9000, 6000, 15000, 10000, 4000, 1000, 250, 59);
		mvc.perform(multipart("/daily-logs").file(dailyLog(day, v1)).with(DESK).with(csrf()))
				.andExpect(redirectedUrl("/daily-logs"));
		assertThat(jdbc.queryForObject("SELECT total_ttc FROM daily_summary WHERE log_date = ?", Double.class, day.toString())).isEqualTo(15000);
		assertThat(jdbc.queryForObject("SELECT drinks_amount_total FROM daily_summary WHERE log_date = ?", Double.class, day.toString())).isEqualTo(250);
		String stored = jdbc.queryForObject("SELECT stored_name FROM daily_import WHERE log_date = ? AND status = 'IMPORTED'", String.class, day.toString());
		assertThat(Files.exists(tempDir.resolve("files").resolve(stored))).isTrue();

		byte[] v2 = TestWorkbooks.dailyLog(9490, 6000, 15490, 10490, 4000, 1000, 250, 59);
		mvc.perform(multipart("/daily-logs").file(dailyLog(day, v2)).with(DESK).with(csrf()));
		assertThat(jdbc.queryForObject("SELECT total_ttc FROM daily_summary WHERE log_date = ?", Double.class, day.toString())).isEqualTo(15000);
		assertThat(jdbc.queryForObject("SELECT message FROM daily_import WHERE log_date = ? ORDER BY id DESC LIMIT 1", String.class, day.toString()))
				.contains("ask an admin");

		mvc.perform(multipart("/daily-logs").file(dailyLog(day, v2)).with(FIN).with(csrf()).param("replace", "true"));
		assertThat(jdbc.queryForObject("SELECT total_ttc FROM daily_summary WHERE log_date = ?", Double.class, day.toString())).isEqualTo(15490);

		long importId = jdbc.queryForObject("SELECT id FROM daily_import WHERE log_date = ? AND status = 'REPLACED'", Long.class, day.toString());
		mvc.perform(get("/daily-logs/" + importId + "/file").with(DESK)).andExpect(status().isForbidden());
		mvc.perform(get("/daily-logs/" + importId + "/file").with(FIN)).andExpect(status().isOk());
	}

	@Test
	void dailyLogChecksRefuseOrWarn() throws Exception
	{
		byte[] good = TestWorkbooks.dailyLog(5000, 0, 5000, 5000, 0, 0, 0, 59);
		mvc.perform(multipart("/daily-logs").file(dailyLog(TODAY.plusDays(1), good)).with(DESK).with(csrf()));
		mvc.perform(multipart("/daily-logs")
				.file(new MockMultipartFile("files", "rapport.xlsx", "application/octet-stream", good)).with(DESK).with(csrf()));
		mvc.perform(multipart("/daily-logs")
				.file(new MockMultipartFile("files", "DL-" + TODAY.minusDays(9).format(DL) + ".xlsx", "text/plain", "hello".getBytes()))
				.with(DESK).with(csrf()));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_import WHERE status = 'REJECTED' AND (message LIKE '%future%' OR message LIKE '%file name%' OR message LIKE '%not an Excel%')", Integer.class))
				.isGreaterThanOrEqualTo(3);

		// Payments do not add up to the total: imported, but flagged
		LocalDate day = TODAY.minusDays(5);
		mvc.perform(multipart("/daily-logs").file(dailyLog(day, TestWorkbooks.dailyLog(7000, 0, 7000, 6500, 0, 0, 0, 59))).with(DESK).with(csrf()));
		assertThat(jdbc.queryForObject("SELECT warnings FROM daily_import WHERE log_date = ? AND status = 'IMPORTED'", String.class, day.toString()))
				.contains("Cash + card + cheque");

		// The same file uploaded under another date: imported, but flagged as a probable copy
		LocalDate other = TODAY.minusDays(6);
		byte[] copied = TestWorkbooks.dailyLog(4321, 0, 4321, 4321, 0, 0, 0, 59);
		mvc.perform(multipart("/daily-logs").file(dailyLog(TODAY.minusDays(7), copied)).with(DESK).with(csrf()));
		mvc.perform(multipart("/daily-logs").file(dailyLog(other, copied)).with(DESK).with(csrf()));
		assertThat(jdbc.queryForObject("SELECT warnings FROM daily_import WHERE log_date = ? AND status = 'IMPORTED'", String.class, other.toString()))
				.contains("identical");
	}

	/* ======================= cash & reserves ======================= */

	@Test
	void onlyAdminsRecordCashMovementsAndCountsCorrectTheSafe() throws Exception
	{
		double safeBefore = finance.safeBalance().balance();
		mvc.perform(post("/reserves/movements").with(FIN).with(csrf())
						.param("action", "RECEPTION_TO_SAFE").param("date", TODAY.toString()).param("amount", "1 000").param("note", "weekly"))
				.andExpect(redirectedUrl("/reserves"));
		assertThat(finance.safeBalance().balance()).isEqualTo(safeBefore + 1000);

		mvc.perform(post("/reserves/count").with(FIN).with(csrf()).param("place", "SAFE").param("counted", "250"));
		assertThat(finance.safeBalance().balance()).isEqualTo(250);

		mvc.perform(post("/reserves/movements").with(DESK).with(csrf())
						.param("action", "SAFE_TO_BANK").param("date", TODAY.toString()).param("amount", "100"))
				.andExpect(status().isForbidden());
	}

	/* ======================= pages & audit ======================= */

	@Test
	void everyPageRendersForTheRolesAllowedToSeeIt() throws Exception
	{
		mvc.perform(multipart("/expenses/new").with(DESK).with(csrf())
				.param("date", TODAY.toString()).param("category", "Cleaning").param("activity", "Cleaning")
				.param("description", "Produits ménage").param("amount", "80"));
		long id = expenseId("Produits ménage");

		for (String page : new String[]{"/expenses", "/expenses/new", "/expenses/" + id, "/expenses/" + id + "/edit", "/daily-logs"})
		{
			mvc.perform(get(page).with(DESK)).andExpect(status().isOk());
		}
		for (String page : new String[]{"/dashboard", "/reserves", "/expenses", "/expenses?status=PENDING", "/expenses?suspects=true",
				"/expenses/import", "/expenses/" + id, "/daily-logs", "/today"})
		{
			mvc.perform(get(page).with(FIN)).andExpect(status().isOk());
		}
		for (String page : new String[]{"/dashboard", "/reserves", "/today", "/salon", "/files"})
		{
			mvc.perform(get(page).with(DESK)).andExpect(status().isForbidden());
		}
	}

	@Test
	void everyChangeIsAuditedAndTheAuditLogCannotBeRewritten() throws Exception
	{
		mvc.perform(multipart("/expenses/new").with(DESK).with(csrf())
				.param("date", TODAY.toString()).param("category", "Other").param("activity", "General")
				.param("description", "Audit me").param("amount", "5"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'EXPENSE_CREATE' AND username = 'desk' AND details LIKE '%Audit me%'", Integer.class))
				.isEqualTo(1);
		assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log")).isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET username = 'someone-else'")).isInstanceOf(DataAccessException.class);
	}
}
