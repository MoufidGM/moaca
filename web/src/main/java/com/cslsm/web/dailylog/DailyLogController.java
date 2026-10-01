package com.cslsm.web.dailylog;

import com.cslsm.web.dailylog.DailyLogRepository.ImportRow;
import com.cslsm.web.support.Actor;
import com.cslsm.web.support.AuditService;
import com.cslsm.web.support.CurrentActor;
import com.cslsm.web.support.FileStorage;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
@RequestMapping("/daily-logs")
@PreAuthorize("hasAnyRole('RECEPTIONIST', 'RESTAURANT_MANAGER', 'ADMIN', 'SUPER_ADMIN')")
public class DailyLogController
{
	/** Enough for an admin to re-import months of history at once (files are ~80 KB). */
	private static final int MAX_FILES_PER_UPLOAD = 100;

	private final DailyLogImportService importer;
	private final DailyLogRepository repo;
	private final FileStorage storage;
	private final CurrentActor currentActor;
	private final AuditService audit;
	private final Clock clock;

	public DailyLogController(DailyLogImportService importer, DailyLogRepository repo, FileStorage storage,
							  CurrentActor currentActor, AuditService audit, Clock clock)
	{
		this.importer = importer;
		this.repo = repo;
		this.storage = storage;
		this.currentActor = currentActor;
		this.audit = audit;
		this.clock = clock;
	}

	@GetMapping
	public String page(Model model)
	{
		Actor actor = currentActor.require();
		LocalDate today = LocalDate.now(clock);
		boolean restaurantOnly = actor.isRestaurant();
		model.addAttribute("canCenter", !restaurantOnly);
		model.addAttribute("canRestaurant", true);
		model.addAttribute("isAdmin", actor.isAdmin());
		model.addAttribute("missingDays", restaurantOnly ? List.of() : repo.missingDays(today.minusDays(14), today.minusDays(1)));
		model.addAttribute("imports", restaurantOnly ? repo.recentImports("RESTAURANT", 60) : repo.recentImports(60));
		return "daily-logs";
	}

	/** The Excel template Tiki Taka or the Salon fills in each day. */
	@GetMapping("/template/{unit}")
	public ResponseEntity<byte[]> template(@PathVariable String unit) throws IOException
	{
		String file = switch (unit)
		{
			case "restaurant" -> "restaurant-log-template.xlsx";
			case "salon" -> "salon-log-template.xlsx";
			default -> throw new ResponseStatusException(NOT_FOUND);
		};
		try (java.io.InputStream in = getClass().getResourceAsStream("/" + file))
		{
			if (in == null)
			{
				throw new ResponseStatusException(NOT_FOUND);
			}
			return ResponseEntity.ok()
					.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
							.filename(("restaurant".equals(unit) ? "TT" : "SA") + "-" + LocalDate.now(clock).format(DateTimeFormatter.ofPattern("dd-MM-yyyy")) + ".xlsx",
									StandardCharsets.UTF_8).build().toString())
					.contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
					.body(in.readAllBytes());
		}
	}

	@PostMapping
	public String upload(@RequestParam(name = "files", required = false) List<MultipartFile> files,
						 @RequestParam(name = "replace", defaultValue = "false") boolean replace,
						 RedirectAttributes redirect)
	{
		Actor actor = currentActor.require();
		List<MultipartFile> chosen = files == null ? List.of()
				: files.stream().filter(f -> f != null && !f.isEmpty()).toList();
		if (chosen.isEmpty())
		{
			redirect.addFlashAttribute("flashError", "Choose at least one daily log file.");
			return "redirect:/daily-logs";
		}
		if (chosen.size() > MAX_FILES_PER_UPLOAD)
		{
			redirect.addFlashAttribute("flashError", "Upload at most " + MAX_FILES_PER_UPLOAD + " files at a time.");
			return "redirect:/daily-logs";
		}
		List<DailyLogImportService.Outcome> outcomes = new ArrayList<>();
		for (MultipartFile f : chosen)
		{
			outcomes.add(importer.importFile(f, actor, replace && actor.isAdmin()));
		}
		redirect.addFlashAttribute("outcomes", outcomes);
		return "redirect:/daily-logs";
	}

	/** The original file behind a day's figures. Admin only. */
	@GetMapping("/{id}/file")
	@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
	public ResponseEntity<byte[]> original(@PathVariable long id) throws IOException
	{
		ImportRow row = repo.findImport(id).filter(r -> r.storedName() != null)
				.orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
		byte[] content = storage.read(row.storedName());
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename(row.originalName(), StandardCharsets.UTF_8).build().toString())
				.contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
				.body(content);
	}

	@PostMapping("/{id}/reviewed")
	@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
	public String markReviewed(@PathVariable long id, RedirectAttributes redirect)
	{
		Actor actor = currentActor.require();
		if (repo.markReviewed(id, actor.displayName()))
		{
			audit.record(actor, "DAILY_LOG_REVIEWED", "daily_import", id, null);
			redirect.addFlashAttribute("flashOk", "Marked as checked.");
		}
		return "redirect:/daily-logs";
	}
}
