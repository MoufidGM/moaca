package com.cslsm.web;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Frequent expenses: an admin makes one from an expense; the form offers it with one tap. */
@SpringBootTest
@AutoConfigureMockMvc
class ExpensePresetFlowTest
{
	private static final LocalDate TODAY = LocalDate.now();

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry)
	{
		registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("preset.db") + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=true");
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
	private static final RequestPostProcessor ROOT = user("root").roles("SUPER_ADMIN");

	@BeforeEach
	void accounts()
	{
		for (String[] u : new String[][]{{"fin", "ADMIN"}, {"desk", "RECEPTIONIST"}, {"root", "SUPER_ADMIN"}})
		{
			if (users.findByUsername(u[0]).isEmpty())
			{
				users.create(u[0], u[0], passwordEncoder.encode("a-long-enough-password"), Role.valueOf(u[1]));
			}
		}
	}

	@Test
	void frequentExpensesFillTheFormWithOneTap() throws Exception
	{
		mvc.perform(multipart("/expenses/new").with(FIN).with(csrf()).param("date", TODAY.toString()).param("category", "Cleaning")
						.param("activity", "General").param("description", "Produits de nettoyage").param("amount", "350").param("paidFrom", "RECEPTION"))
				.andExpect(status().is3xxRedirection());
		long id = jdbc.queryForObject("SELECT id FROM expense WHERE description = 'Produits de nettoyage'", Long.class);
		mvc.perform(post("/expenses/" + id + "/preset").with(DESK).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(post("/expenses/" + id + "/preset").with(FIN).with(csrf()).param("name", "Nettoyage"))
				.andExpect(flash().attribute("flashOk", containsString("frequent expense")));
		long preset = jdbc.queryForObject("SELECT id FROM expense_preset WHERE name = 'Nettoyage'", Long.class);
		mvc.perform(get("/expenses/new").with(DESK)).andExpect(content().string(containsString("Nettoyage")));
		mvc.perform(get("/expenses/new").with(DESK).param("preset", String.valueOf(preset)))
				.andExpect(model().attribute("form", hasProperty("amount", is("350"))))
				.andExpect(model().attribute("form", hasProperty("category", is("Cleaning"))));
		mvc.perform(post("/expenses/" + id + "/preset").with(FIN).with(csrf()).param("name", "nettoyage"))
				.andExpect(flash().attribute("flashError", containsString("already")));
		mvc.perform(post("/admin/presets/" + preset).with(ROOT).with(csrf()).param("active", "false"))
				.andExpect(redirectedUrl("/admin#presets"));
		mvc.perform(get("/expenses/new").with(DESK)).andExpect(content().string(not(containsString("Nettoyage"))));
	}
}
