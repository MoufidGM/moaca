package com.cslsm.web.reports;

import com.cslsm.web.reports.ReportService.Def;
import com.cslsm.web.reports.ReportService.Report;
import com.cslsm.web.support.Actor;
import com.cslsm.web.support.AuditService;
import com.cslsm.web.support.CurrentActor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Yearly reports with CSV and Excel downloads. Admins only. */
@Controller
@RequestMapping("/reports")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class ReportsController
{
	private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
	private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");

	private final ReportService reports;
	private final CurrentActor currentActor;
	private final AuditService audit;
	private final Clock clock;

	public ReportsController(ReportService reports, CurrentActor currentActor, AuditService audit, Clock clock)
	{
		this.reports = reports;
		this.currentActor = currentActor;
		this.audit = audit;
		this.clock = clock;
	}

	@GetMapping
	public String page(@RequestParam(required = false) String report, @RequestParam(required = false) Integer year, Model model)
	{
		LocalDate today = LocalDate.now(clock);
		Def def = ReportService.def(report).orElse(ReportService.DEFS.get(0));
		List<Integer> years = reports.years(today);
		int y = year != null && years.contains(year) ? year : today.getYear();
		model.addAttribute("report", reports.build(def, y, today));
		model.addAttribute("defs", ReportService.DEFS);
		model.addAttribute("years", years);
		return "reports";
	}

	@GetMapping("/export")
	public ResponseEntity<byte[]> export(@RequestParam String report, @RequestParam int year,
										 @RequestParam(defaultValue = "xlsx") String format)
	{
		Actor actor = currentActor.require();
		LocalDate today = LocalDate.now(clock);
		Def def = ReportService.def(report).orElse(ReportService.DEFS.get(0));
		int y = reports.years(today).contains(year) ? year : today.getYear();
		Report r = reports.build(def, y, today);
		boolean csv = "csv".equalsIgnoreCase(format);
		byte[] body = csv ? ReportExport.csv(r) : ReportExport.xlsx(r);
		String fileName = ReportExport.fileName(r, csv ? "csv" : "xlsx");
		audit.record(actor, "REPORT_EXPORT", "report", def.key(), fileName);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename(fileName, StandardCharsets.UTF_8).build().toString())
				.contentType(csv ? CSV : XLSX)
				.body(body);
	}
}
