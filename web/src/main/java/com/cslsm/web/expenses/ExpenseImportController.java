package com.cslsm.web.expenses;

import com.cslsm.web.expenses.ExpenseModels.ExpenseRuleException;
import com.cslsm.web.support.Actor;
import com.cslsm.web.support.CurrentActor;
import java.nio.charset.StandardCharsets;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import com.cslsm.web.support.FileStorage;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.InputStream;

/** Import an expense sheet — upload, preview, confirm. Every role; the service applies each role's limits. */
@Controller
@RequestMapping("/expenses/import")
@PreAuthorize("hasAnyRole('RECEPTIONIST', 'RESTAURANT_MANAGER', 'ADMIN', 'SUPER_ADMIN')")
public class ExpenseImportController
{
	private static final String SESSION_KEY = "expenseImportPreview";

	private final ExpenseImportService importer;
	private final ExpenseImportRepository imports;
	private final FileStorage storage;
	private final CurrentActor currentActor;

	public ExpenseImportController(ExpenseImportService importer, ExpenseImportRepository imports, FileStorage storage,
								   CurrentActor currentActor)
	{
		this.importer = importer;
		this.imports = imports;
		this.storage = storage;
		this.currentActor = currentActor;
	}

	@GetMapping
	public String page(Model model)
	{
		common(model);
		return "expense-import";
	}

	private void common(Model model)
	{
		Actor actor = currentActor.require();
		model.addAttribute("units", ExpenseImportService.unitsFor(actor));
		model.addAttribute("isAdmin", actor.isAdmin());
	}

	@PostMapping
	public String preview(@RequestParam(name = "file", required = false) MultipartFile file,
						  @RequestParam(defaultValue = "CENTER") String unit, HttpSession session, Model model)
	{
		Actor actor = currentActor.require();
		common(model);
		if (file == null || file.isEmpty())
		{
			model.addAttribute("error", "Choose an Excel file.");
			return "expense-import";
		}
		try
		{
			ExpenseImportService.Preview preview = importer.preview(file.getOriginalFilename(), file.getBytes(), unit, actor);
			session.setAttribute(SESSION_KEY, preview);
			model.addAttribute("preview", preview);
			model.addAttribute("unitLabel", ExpenseImportService.unitLabel(preview.unit()));
		}
		catch (ExpenseRuleException | IOException e)
		{
			model.addAttribute("error", e instanceof IOException ? "The upload was interrupted. Try again." : e.getMessage());
		}
		return "expense-import";
	}

	/** The uploaded sheet itself. Admins only. */
	@GetMapping("/{id}/file")
	@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
	public ResponseEntity<byte[]> file(@PathVariable long id) throws IOException
	{
		ExpenseImportRepository.ImportedSheet sheet = imports.find(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename(sheet.originalName(), StandardCharsets.UTF_8).build().toString())
				.contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
				.body(storage.read(sheet.storedName()));
	}

	@PostMapping("/confirm")
	public String confirm(@RequestParam String token, HttpSession session, RedirectAttributes redirect)
	{
		Actor actor = currentActor.require();
		Object stored = session.getAttribute(SESSION_KEY);
		if (!(stored instanceof ExpenseImportService.Preview preview) || !preview.token().equals(token))
		{
			redirect.addFlashAttribute("flashError", "This preview has expired. Upload the file again.");
			return "redirect:/expenses/import";
		}
		session.removeAttribute(SESSION_KEY);
		try
		{
			int saved = importer.confirm(preview, actor);
			redirect.addFlashAttribute("flashOk", saved + " expense(s) imported" + (actor.isAdmin() ? "" : " — they wait for an admin's approval")
					+ (preview.errorCount() > 0 ? "; " + preview.errorCount() + " row(s) with errors were skipped." : "."));
		}
		catch (ExpenseRuleException e)
		{
			redirect.addFlashAttribute("flashError", e.getMessage());
		}
		return "redirect:/expenses";
	}

	/** Blank sheet with the expected columns and an example row. */
	@GetMapping("/template")
	public ResponseEntity<byte[]> template() throws IOException
	{
		try (InputStream in = new ClassPathResource("expense-template.xlsx").getInputStream())
		{
			return ResponseEntity.ok()
					.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("Expenses-template.xlsx").build().toString())
					.contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
					.body(in.readAllBytes());
		}
	}
}
