package com.cslsm.web;

import com.cslsm.web.expenses.ExpenseImportService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Expense sheets from the reception and the restaurant manager wait for approval; every sheet is kept and listed for admins. */
@SpringBootTest
@AutoConfigureMockMvc
class ExpenseImportFlowTest
{
	private static final LocalDate TODAY = LocalDate.now();
	private static final DateTimeFormatter DL = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry)
	{
		registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("import.db") + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=true");
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

	private ExpenseImportService.Preview previewOf(RequestPostProcessor who, String unit, String fileName, byte[] sheet, MockHttpSession[] sessionOut) throws Exception
	{
		MvcResult r = mvc.perform(multipart("/expenses/import").file(new MockMultipartFile("file", fileName, "application/octet-stream", sheet))
						.param("unit", unit).with(who).with(csrf()))
				.andExpect(status().isOk()).andReturn();
		sessionOut[0] = (MockHttpSession) r.getRequest().getSession();
		return (ExpenseImportService.Preview) r.getModelAndView().getModel().get("preview");
	}

	@Test
	void receptionAndManagerSheetsWaitForApprovalAndEveryFileIsKept() throws Exception
	{
		String d = TODAY.minusDays(2).format(DL);
		// The reception's sheet for the center: a Salaries row is refused, "bank" becomes the till, rows are pending
		byte[] sheet = TestWorkbooks.expenseSheet(
				new Object[]{d, "Supplies", "Balles", 120, "Cash", null, null, "Gym", "yes"},
				new Object[]{d, "Salaries", "Salaire", 5000, "Cash", null, null, "General", "no"},
				new Object[]{d, "Cleaning", "Javel", 40, "Cash", null, null, "General", "no"});
		MockHttpSession[] session = new MockHttpSession[1];
		ExpenseImportService.Preview p = previewOf(DESK, "CENTER", "depenses-reception.xlsx", sheet, session);
		assertThat(p.okCount()).isEqualTo(2);
		assertThat(p.rows().get(1).errors()).anyMatch(e -> e.contains("admin"));
		assertThat(p.rows().get(2).paidFrom()).isEqualTo("RECEPTION");
		mvc.perform(post("/expenses/import/confirm").session(session[0]).with(DESK).with(csrf()).param("token", p.token()))
				.andExpect(flash().attribute("flashOk", containsString("wait for an admin")));
		Map<String, Object> balles = jdbc.queryForMap("SELECT * FROM expense WHERE description = 'Balles'");
		assertThat(balles.get("status")).isEqualTo("PENDING");
		assertThat(balles.get("activity")).isEqualTo("Gym");
		assertThat(balles.get("entered_by")).isEqualTo("desk");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense WHERE description = 'Salaire'", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_import WHERE unit = 'CENTER' AND status = 'PENDING' AND rows_saved = 2", Integer.class)).isEqualTo(1);

		// The manager's sheet: only Tiki Taka, from the restaurant till, whatever the sheet says
		byte[] tt = TestWorkbooks.expenseSheet(new Object[]{d, "Food & drinks", "Marché", 900, "Cash", null, null, "Gym", "yes"});
		mvc.perform(multipart("/expenses/import").file(new MockMultipartFile("file", "tt.xlsx", "application/octet-stream", tt))
						.param("unit", "CENTER").with(CHEF).with(csrf()))
				.andExpect(content().string(containsString("Say whether")));
		ExpenseImportService.Preview tp = previewOf(CHEF, "RESTAURANT", "tt.xlsx", tt, session);
		assertThat(tp.rows().get(0).activity()).isEqualTo("Tiki Taka");
		assertThat(tp.rows().get(0).paidFrom()).isEqualTo("RESTAURANT");
		mvc.perform(post("/expenses/import/confirm").session(session[0]).with(CHEF).with(csrf()).param("token", tp.token()));
		Map<String, Object> marche = jdbc.queryForMap("SELECT * FROM expense WHERE description = 'Marché'");
		assertThat(marche.get("till")).isEqualTo("RESTAURANT");
		assertThat(marche.get("status")).isEqualTo("PENDING");

		// The admin's Salon sheet: approved, on the Salon activity, paid from the bank as written
		byte[] sa = TestWorkbooks.expenseSheet(new Object[]{d, "Supplies", "Shampoing", 300, "Card", null, null, null, "no"});
		ExpenseImportService.Preview sp = previewOf(FIN, "SALON", "salon.xlsx", sa, session);
		assertThat(sp.rows().get(0).activity()).isEqualTo("Salon");
		assertThat(sp.rows().get(0).paidFrom()).isEqualTo("BANK");
		mvc.perform(post("/expenses/import/confirm").session(session[0]).with(FIN).with(csrf()).param("token", sp.token()))
				.andExpect(flash().attribute("flashOk", containsString("1 expense(s) imported.")));
		assertThat(jdbc.queryForMap("SELECT * FROM expense WHERE description = 'Shampoing'").get("status")).isEqualTo("APPROVED");

		// Every sheet is kept on disk and listed for admins by month; the reception cannot see the list or the files
		long sheetId = jdbc.queryForObject("SELECT id FROM expense_import WHERE original_name = 'tt.xlsx'", Long.class);
		mvc.perform(get("/files").with(FIN).param("month", TODAY.minusDays(2).toString().substring(0, 7)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("depenses-reception.xlsx")))
				.andExpect(content().string(containsString("tt.xlsx")))
				.andExpect(content().string(containsString("Tiki Taka")));
		mvc.perform(get("/files").with(FIN).param("kind", "daily").param("month", TODAY.toString().substring(0, 7)))
				.andExpect(content().string(org.hamcrest.Matchers.not(containsString("tt.xlsx"))));
		mvc.perform(get("/expenses/import/" + sheetId + "/file").with(FIN)).andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", containsString("tt.xlsx")));
		mvc.perform(get("/expenses/import/" + sheetId + "/file").with(DESK)).andExpect(status().isForbidden());
		mvc.perform(get("/files").with(DESK)).andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'EXPENSE_IMPORT'", Integer.class)).isEqualTo(3);
	}
}
