package com.cslsm.web.dailylog;

import com.cslsm.web.dailylog.DailyLogParser.DailyLogException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Tiki Taka's and the Salon's daily files (TT-dd-MM-yyyy.xlsx, SA-dd-MM-yyyy.xlsx): a
 * summary block with the day's total, the card amount, the covers or clients and a note, at
 * cells given by restaurant-log-layout.properties / salon-log-layout.properties. Cash is the
 * total minus card. Same cell reading as the center's parser.
 */
public final class UnitLogParser
{
	/** Which file, and where its figures are. */
	public static final class Layout
	{
		public final String unit;
		public final String prefix;
		final int totalRow, totalCol, cardRow, cardCol, peopleRow, peopleCol, noteRow, noteCol;

		public Layout(String unit, Properties p)
		{
			this.unit = unit;
			this.prefix = p.getProperty("file.prefix", unit.substring(0, 2)).trim();
			totalRow = i(p, "total.row", 5) - 1;
			totalCol = i(p, "total.col", 2) - 1;
			cardRow = i(p, "card.row", 6) - 1;
			cardCol = i(p, "card.col", 2) - 1;
			peopleRow = i(p, "people.row", 8) - 1;
			peopleCol = i(p, "people.col", 2) - 1;
			noteRow = i(p, "note.row", 9) - 1;
			noteCol = i(p, "note.col", 2) - 1;
		}

		private static int i(Properties p, String key, int def)
		{
			try
			{
				String raw = p.getProperty(key);
				return raw == null || raw.isBlank() ? def : Integer.parseInt(raw.trim());
			}
			catch (NumberFormatException e)
			{
				return def;
			}
		}
	}

	/** The figures of one day. */
	public record ParsedUnitDay(String unit, LocalDate date, double total, double card, Integer people, String note)
	{
		public double cash()
		{
			return Math.round((total - card) * 100) / 100.0;
		}

		public boolean isEmpty()
		{
			return total == 0 && card == 0;
		}
	}

	private final Layout layout;
	private final Pattern fileName;

	public UnitLogParser(Layout layout)
	{
		this.layout = layout;
		this.fileName = Pattern.compile("(?i)^" + Pattern.quote(layout.prefix) + "-(\\d{2})-(\\d{2})-(\\d{4})(?: \\(\\d+\\))?\\.(xlsx|xls)$");
	}

	public Layout layout()
	{
		return layout;
	}

	/** Whether a file name is this unit's (prefix match); the date is checked by dateFromFileName. */
	public boolean owns(String name)
	{
		return name != null && name.trim().toUpperCase().startsWith(layout.prefix.toUpperCase() + "-");
	}

	public LocalDate dateFromFileName(String name) throws DailyLogException
	{
		Matcher m = fileName.matcher(name == null ? "" : name.trim());
		if (!m.matches())
		{
			throw new DailyLogException("The file name must look like " + layout.prefix + "-29-09-2026.xlsx (day-month-year).");
		}
		try
		{
			return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
		}
		catch (DateTimeException e)
		{
			throw new DailyLogException("The date in the file name does not exist: " + m.group(1) + "-" + m.group(2) + "-" + m.group(3) + ".");
		}
	}

	public ParsedUnitDay parse(InputStream in, LocalDate date) throws DailyLogException
	{
		try (Workbook wb = WorkbookFactory.create(in))
		{
			if (wb.getNumberOfSheets() == 0)
			{
				throw new DailyLogException("The workbook has no sheets.");
			}
			Sheet sh = wb.getSheetAt(0);
			double total = DailyLogParser.read(sh, layout.totalRow, layout.totalCol);
			double card = DailyLogParser.read(sh, layout.cardRow, layout.cardCol);
			double people = DailyLogParser.read(sh, layout.peopleRow, layout.peopleCol);
			String note = text(sh, layout.noteRow, layout.noteCol);
			return new ParsedUnitDay(layout.unit, date, total, card, people <= 0 ? null : (int) Math.round(people), note);
		}
		catch (DailyLogException e)
		{
			throw e;
		}
		catch (IOException | RuntimeException e)
		{
			throw new DailyLogException("This file could not be read as an Excel workbook. Save it again as .xlsx and retry.");
		}
	}

	private static String text(Sheet sh, int row0, int col0)
	{
		Row r = sh.getRow(row0);
		Cell c = r == null ? null : r.getCell(col0);
		if (c == null || c.getCellType() != CellType.STRING)
		{
			return null;
		}
		String s = c.getStringCellValue().trim();
		return s.isEmpty() ? null : (s.length() > 200 ? s.substring(0, 200) : s);
	}
}
