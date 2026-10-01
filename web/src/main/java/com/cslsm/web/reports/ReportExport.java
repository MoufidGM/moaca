package com.cslsm.web.reports;

import com.cslsm.web.reports.ReportService.Report;
import com.cslsm.web.reports.ReportTable.Cell;
import com.cslsm.web.reports.ReportTable.Kind;
import com.cslsm.web.reports.ReportTable.Row;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A report as a CSV or Excel file — the same cells as on screen. Labels can be names typed
 * by people (categories, activities), so CSV cells that a spreadsheet would run as a formula
 * are neutralised, and Excel cells are always written as text or numbers, never formulas.
 */
public final class ReportExport
{
	private ReportExport()
	{
	}

	public static String fileName(Report r, String extension)
	{
		return "cslsm-" + r.def().key() + "-" + r.year() + "." + extension;
	}

	/* ======================= CSV ======================= */

	/** UTF-8 with a byte-order mark so Excel reads accents; comma-separated; CRLF lines. */
	public static byte[] csv(Report r)
	{
		StringBuilder s = new StringBuilder();
		s.append('﻿');
		s.append(csvCell(r.def().title() + " " + r.year()));
		for (String column : r.table().columns())
		{
			s.append(',').append(csvCell(column));
		}
		s.append("\r\n");
		for (Row row : r.table().rows())
		{
			s.append(csvCell(row.label()));
			// Numbers are generated here, never typed by anyone, so they go in as they are
			for (Cell c : row.cells())
			{
				s.append(',').append(c.value() == null ? "" : c.percent() ? c.display() : String.format(Locale.ROOT, "%.2f", c.value()));
			}
			s.append("\r\n");
		}
		return s.toString().getBytes(StandardCharsets.UTF_8);
	}

	/** Quotes when needed; a leading =, +, -, @ (or tab/CR) gets an apostrophe so it stays text. */
	static String csvCell(String value)
	{
		if (value == null || value.isEmpty())
		{
			return "";
		}
		String v = value;
		char first = v.charAt(0);
		if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r')
		{
			v = "'" + v;
		}
		if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r") || v.startsWith("'"))
		{
			return "\"" + v.replace("\"", "\"\"") + "\"";
		}
		return v;
	}

	/* ======================= Excel ======================= */

	public static byte[] xlsx(Report r)
	{
		try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream())
		{
			Sheet sheet = wb.createSheet(WorkbookUtil.createSafeSheetName(r.def().title()));

			Font bold = wb.createFont();
			bold.setBold(true);
			CellStyle boldStyle = wb.createCellStyle();
			boldStyle.setFont(bold);
			CellStyle money = wb.createCellStyle();
			money.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
			CellStyle moneyBold = wb.createCellStyle();
			moneyBold.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
			moneyBold.setFont(bold);
			CellStyle percent = wb.createCellStyle();
			percent.setDataFormat(wb.createDataFormat().getFormat("0%"));

			int rowNo = 0;
			org.apache.poi.ss.usermodel.Row title = sheet.createRow(rowNo++);
			org.apache.poi.ss.usermodel.Cell t = title.createCell(0);
			t.setCellValue(r.def().title() + " " + r.year());
			t.setCellStyle(boldStyle);
			if (r.note() != null)
			{
				sheet.createRow(rowNo++).createCell(0).setCellValue(r.note());
			}
			rowNo++;

			org.apache.poi.ss.usermodel.Row header = sheet.createRow(rowNo++);
			for (int i = 0; i < r.table().columns().size(); i++)
			{
				org.apache.poi.ss.usermodel.Cell c = header.createCell(i + 1);
				c.setCellValue(r.table().columns().get(i));
				c.setCellStyle(boldStyle);
			}
			for (Row row : r.table().rows())
			{
				org.apache.poi.ss.usermodel.Row x = sheet.createRow(rowNo++);
				org.apache.poi.ss.usermodel.Cell label = x.createCell(0);
				label.setCellValue(row.label());
				boolean strong = row.kind() != Kind.NORMAL;
				if (strong)
				{
					label.setCellStyle(boldStyle);
				}
				for (int i = 0; i < row.cells().size(); i++)
				{
					Cell c = row.cells().get(i);
					if (c.value() == null)
					{
						continue;
					}
					org.apache.poi.ss.usermodel.Cell cell = x.createCell(i + 1);
					cell.setCellValue(c.value());
					cell.setCellStyle(c.percent() ? percent : strong ? moneyBold : money);
				}
			}
			sheet.createFreezePane(1, header.getRowNum() + 1);
			sheet.setColumnWidth(0, 36 * 256);
			for (int i = 1; i <= r.table().columns().size(); i++)
			{
				sheet.setColumnWidth(i, 13 * 256);
			}
			wb.write(out);
			return out.toByteArray();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}
}
