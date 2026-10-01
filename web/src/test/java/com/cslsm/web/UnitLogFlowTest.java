package com.cslsm.web;

import com.cslsm.web.finance.FinanceRepository;
import com.cslsm.web.security.Role;
import com.cslsm.web.security.UserRepository;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tiki Taka's (TT-) and the Salon's (SA-) daily files, uploaded next to the center's. */
@SpringBootTest
@AutoConfigureMockMvc
class UnitLogFlowTest
{
	private static final LocalDate TODAY = LocalDate.now();
	private static final DateTimeFormatter DL = DateTimeFormatter.ofPattern("dd-MM-yyyy");

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry)
	{
		registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("unitlog.db") + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=true");
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

	/** A file like the template's summary block: B5 total, B6 card, B8 people, B9 note. */
	private static byte[] unitFile(double total, double card, int people, String note) throws Exception
	{
		try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream())
		{
			Sheet sh = wb.createSheet("Daily");
			sh.createRow(4).createCell(1).setCellValue(total);
			sh.createRow(5).createCell(1).setCellValue(card);
			sh.createRow(7).createCell(1).setCellValue(people);
			sh.createRow(8).createCell(1).setCellValue(note);
			wb.write(out);
			return out.toByteArray();
		}
	}

	private static MockMultipartFile upload(String prefix, LocalDate date, byte[] content)
	{
		return new MockMultipartFile("files", prefix + "-" + date.format(DL) + ".xlsx", "application/octet-stream", content);
	}

	@Test
	void restaurantAndSalonFilesLandInTheirSalesTables() throws Exception
	{
		LocalDate day = TODAY.minusDays(1);

		// The chef uploads Tiki Taka's file
		mvc.perform(multipart("/daily-logs").file(upload("TT", day, unitFile(450, 100, 12, "Soirée"))).with(CHEF).with(csrf()))
				.andExpect(status().is3xxRedirection());
		Map<String, Object> sale = jdbc.queryForMap("SELECT * FROM restaurant_sales WHERE sale_date = ?", day.toString());
		assertThat(((Number) sale.get("cash")).doubleValue()).isEqualTo(350);
		assertThat(((Number) sale.get("card")).doubleValue()).isEqualTo(100);
		assertThat(((Number) sale.get("covers")).intValue()).isEqualTo(12);
		assertThat(sale.get("note")).isEqualTo("Soirée");
		assertThat(jdbc.queryForObject("SELECT unit FROM daily_import WHERE original_name LIKE 'TT-%' AND status = 'IMPORTED'", String.class)).isEqualTo("RESTAURANT");
		// The reception uploads all three units' files, Tiki Taka's included
		mvc.perform(multipart("/daily-logs").file(upload("TT", day.minusDays(1), unitFile(300, 0, 0, null))).with(DESK).with(csrf()));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_sales", Integer.class)).isEqualTo(2);

		// The chef may not upload the center's or the Salon's
		byte[] center = TestWorkbooks.dailyLog(9000, 6000, 15000, 10000, 4000, 1000, 250, 59);
		mvc.perform(multipart("/daily-logs").file(upload("DL", day, center)).with(CHEF).with(csrf()));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_summary", Integer.class)).isZero();
		mvc.perform(multipart("/daily-logs").file(upload("SA", day, unitFile(200, 50, 3, null))).with(CHEF).with(csrf()));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM salon_sales", Integer.class)).isZero();

		// The reception uploads the Salon's; a second upload of the same day is refused unless an admin replaces
		mvc.perform(multipart("/daily-logs").file(upload("SA", day, unitFile(200, 50, 3, null))).with(DESK).with(csrf()));
		assertThat(jdbc.queryForObject("SELECT cash FROM salon_sales WHERE sale_date = ?", Double.class, day.toString())).isEqualTo(150);
		mvc.perform(multipart("/daily-logs").file(upload("SA", day, unitFile(260, 60, 4, null))).with(DESK).with(csrf()));
		assertThat(jdbc.queryForObject("SELECT cash FROM salon_sales WHERE sale_date = ?", Double.class, day.toString())).isEqualTo(150);
		mvc.perform(multipart("/daily-logs").file(upload("SA", day, unitFile(260, 60, 4, null))).param("replace", "true").with(FIN).with(csrf()));
		assertThat(jdbc.queryForObject("SELECT cash FROM salon_sales WHERE sale_date = ?", Double.class, day.toString())).isEqualTo(200);
		assertThat(finance.income(day, day)).isEqualTo(450 + 260);

		// Bad files are refused with a reason; card above total is a warning
		mvc.perform(multipart("/daily-logs").file(upload("TT", TODAY.plusDays(1), unitFile(1, 0, 0, null))).with(CHEF).with(csrf()));
		assertThat(jdbc.queryForObject("SELECT message FROM daily_import WHERE log_date = ?", String.class, TODAY.plusDays(1).toString())).contains("future");
		mvc.perform(multipart("/daily-logs").file(upload("TT", day.minusDays(2), unitFile(0, 0, 0, null))).with(CHEF).with(csrf()));
		assertThat(jdbc.queryForObject("SELECT message FROM daily_import WHERE log_date = ?", String.class, day.minusDays(2).toString())).contains("No figures");
		mvc.perform(multipart("/daily-logs").file(upload("TT", day.minusDays(3), unitFile(100, 150, 0, null))).with(CHEF).with(csrf()));
		assertThat(jdbc.queryForObject("SELECT warnings FROM daily_import WHERE log_date = ?", String.class, day.minusDays(3).toString())).contains("more than");

		// Pages and templates
		mvc.perform(get("/daily-logs").with(CHEF)).andExpect(status().isOk())
				.andExpect(content().string(containsString("TT-")))
				.andExpect(content().string(not(containsString("Missing in the last 14 days"))));
		mvc.perform(get("/daily-logs").with(DESK)).andExpect(status().isOk())
				.andExpect(content().string(containsString("SA-"))).andExpect(content().string(containsString("TT-")));
		mvc.perform(get("/daily-logs/template/restaurant").with(CHEF)).andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", containsString("TT-")));
		mvc.perform(get("/daily-logs/template/salon").with(DESK)).andExpect(status().isOk());
		mvc.perform(get("/daily-logs/template/nope").with(DESK)).andExpect(status().isNotFound());
		mvc.perform(get("/restaurant").with(CHEF)).andExpect(content().string(containsString("template")));
	}
}
