package com.cslsm.web.reports;

import com.cslsm.web.reports.ReportService.Def;
import com.cslsm.web.reports.ReportService.Report;
import com.cslsm.web.reports.ReportTable.Cell;
import com.cslsm.web.reports.ReportTable.Kind;
import com.cslsm.web.reports.ReportTable.Row;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportExportTest
{
	@Test
	void csvCellsNeverBecomeFormulas()
	{
		assertThat(ReportExport.csvCell("Padel")).isEqualTo("Padel");
		assertThat(ReportExport.csvCell("=1+1")).isEqualTo("\"'=1+1\"");
		assertThat(ReportExport.csvCell("+cmd")).isEqualTo("\"'+cmd\"");
		assertThat(ReportExport.csvCell("Food, drinks")).isEqualTo("\"Food, drinks\"");
		assertThat(ReportExport.csvCell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
		assertThat(ReportExport.csvCell(null)).isEmpty();
	}

	@Test
	void csvAndExcelCarryTheSameCells()
	{
		Report r = new Report(new Def("pnl", "Profit & loss", ""), 2026, "note",
				new ReportTable(List.of("Jan", "Total"), List.of(
						Row.section("Revenue"),
						new Row("Padel", List.of(Cell.money(1234.5), Cell.money(1234.5)), Kind.NORMAL, false),
						new Row("Net profit", List.of(Cell.money(-10.0), Cell.EMPTY), Kind.TOTAL, true),
						new Row("Margin", List.of(Cell.percent(0.125), Cell.EMPTY), Kind.NORMAL, false))), null);
		String csv = new String(ReportExport.csv(r), StandardCharsets.UTF_8);
		assertThat(csv).startsWith("﻿Profit & loss 2026,Jan,Total\r\n")
				.contains("Revenue\r\n")
				.contains("Padel,1234.50,1234.50\r\n")
				.contains("Net profit,-10.00,\r\n")
				.contains("Margin,13%,\r\n");
		assertThat(ReportExport.fileName(r, "csv")).isEqualTo("cslsm-pnl-2026.csv");

		byte[] xlsx = ReportExport.xlsx(r);
		assertThat(xlsx.length).isGreaterThan(1000);
		assertThat(new String(xlsx, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");

		assertThat(Cell.money(-0.2).display()).isEqualTo("0");
		assertThat(Cell.money(-1500.0).display()).isEqualTo("-1,500");
		assertThat(Cell.EMPTY.display()).isEqualTo("—");
	}
}
