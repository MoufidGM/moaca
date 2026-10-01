package com.cslsm.web.dailylog;

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
 * Reads a daily log workbook (DL-dd-MM-yyyy.xlsx) into figures.
 *
 * Cell for cell the same as the desktop importer (ImportService.parseExcel): same layout
 * file (daily-log-layout.properties, copied unchanged), same cells, same number parsing.
 * No database access — validation and storage happen in DailyLogImportService.
 */
public final class DailyLogParser
{
	/** DL-dd-MM-yyyy.xlsx; also accepts the " (1)" that browsers add to re-downloaded files. */
	private static final Pattern FILE_NAME =
			Pattern.compile("(?i)^DL-(\\d{2})-(\\d{2})-(\\d{4})(?: \\(\\d+\\))?\\.(xlsx|xls)$");

	/** Cell positions, 0-based. */
	public static final class Layout
	{
		final int summaryRow;
		final int terrain, padel, gym, park, miniGolf, pingPong, academy, taekwondo, shoes, totalTtc;
		/** −1 when the sheet has no such column. */
		final int box, dance;
		final int drinksRow, drinksCol;
		final int paymentsRow, cash, card, cheque;

		public Layout(Properties p)
		{
			summaryRow = (i(p, "summary.headerRow", 9) - 1) + i(p, "summary.valueRowOffset", 1);
			terrain = i(p, "summary.col.terrain", 1) - 1;
			padel = i(p, "summary.col.padel", 4) - 1;
			gym = i(p, "summary.col.gym", 7) - 1;
			park = i(p, "summary.col.park", 10) - 1;
			miniGolf = i(p, "summary.col.mini_golf", 13) - 1;
			pingPong = i(p, "summary.col.ping_pong", 16) - 1;
			academy = i(p, "summary.col.academy_foot", 19) - 1;
			taekwondo = i(p, "summary.col.taekwondo", 22) - 1;
			shoes = i(p, "summary.col.shoes", 26) - 1;
			box = i(p, "summary.col.box", 0) - 1;
			dance = i(p, "summary.col.dance", 0) - 1;
			totalTtc = i(p, "summary.col.total_ttc", 29) - 1;
			drinksRow = i(p, "drinks.amt.row", 58) - 1;
			drinksCol = i(p, "drinks.amt.col", 31) - 1;
			paymentsRow = i(p, "payments.row", 14) - 1;
			cash = i(p, "payments.col.cash", 1) - 1;
			card = i(p, "payments.col.card", 13) - 1;
			cheque = i(p, "payments.col.cheque", 19) - 1;
		}

		private static int i(Properties p, String key, int def)
		{
			String raw = p.getProperty(key);
			if (raw == null || raw.trim().isEmpty())
			{
				return def;
			}
			try
			{
				return Integer.parseInt(raw.trim());
			}
			catch (NumberFormatException e)
			{
				return def;
			}
		}
	}

	/** The figures read from one day's file. Departments exclude drinks, like the file's own total. */
	public static final class ParsedDay
	{
		public final LocalDate date;
		public final double terrain, padel, gym, park, miniGolf, pingPong, academy, taekwondo, shoes;
		public final double box, dance;
		public final double totalTtc, drinks, cash, card, cheque;

		ParsedDay(LocalDate date, double terrain, double padel, double gym, double park, double miniGolf,
				  double pingPong, double academy, double taekwondo, double shoes, double totalTtc,
				  double drinks, double cash, double card, double cheque)
		{
			this(date, terrain, padel, gym, park, miniGolf, pingPong, academy, taekwondo, shoes, 0, 0, totalTtc, drinks, cash, card, cheque);
		}

		ParsedDay(LocalDate date, double terrain, double padel, double gym, double park, double miniGolf,
				  double pingPong, double academy, double taekwondo, double shoes, double box, double dance,
				  double totalTtc, double drinks, double cash, double card, double cheque)
		{
			this.box = box;
			this.dance = dance;
			this.date = date;
			this.terrain = terrain;
			this.padel = padel;
			this.gym = gym;
			this.park = park;
			this.miniGolf = miniGolf;
			this.pingPong = pingPong;
			this.academy = academy;
			this.taekwondo = taekwondo;
			this.shoes = shoes;
			this.totalTtc = totalTtc;
			this.drinks = drinks;
			this.cash = cash;
			this.card = card;
			this.cheque = cheque;
		}

		public double departmentsTotal()
		{
			return terrain + padel + gym + park + miniGolf + pingPong + academy + taekwondo + shoes + box + dance;
		}

		public double paymentsTotal()
		{
			return cash + card + cheque;
		}

		public boolean isEmpty()
		{
			return totalTtc == 0 && departmentsTotal() == 0 && paymentsTotal() == 0;
		}
	}

	/** A problem the uploader can fix (wrong name, unreadable file…). The message is shown as-is. */
	public static final class DailyLogException extends Exception
	{
		public DailyLogException(String message)
		{
			super(message);
		}
	}

	private final Layout layout;

	public DailyLogParser(Layout layout)
	{
		this.layout = layout;
	}

	public static LocalDate dateFromFileName(String fileName) throws DailyLogException
	{
		Matcher m = FILE_NAME.matcher(fileName == null ? "" : fileName.trim());
		if (!m.matches())
		{
			throw new DailyLogException("The file name must look like DL-29-09-2026.xlsx (day-month-year).");
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

	public ParsedDay parse(InputStream in, LocalDate date) throws DailyLogException
	{
		try (Workbook wb = WorkbookFactory.create(in))
		{
			if (wb.getNumberOfSheets() == 0)
			{
				throw new DailyLogException("The workbook has no sheets.");
			}
			Sheet sh = wb.getSheetAt(0);
			Layout l = layout;
			return new ParsedDay(date,
					read(sh, l.summaryRow, l.terrain),
					read(sh, l.summaryRow, l.padel),
					read(sh, l.summaryRow, l.gym),
					read(sh, l.summaryRow, l.park),
					read(sh, l.summaryRow, l.miniGolf),
					read(sh, l.summaryRow, l.pingPong),
					read(sh, l.summaryRow, l.academy),
					read(sh, l.summaryRow, l.taekwondo),
					read(sh, l.summaryRow, l.shoes),
					l.box < 0 ? 0 : read(sh, l.summaryRow, l.box),
					l.dance < 0 ? 0 : read(sh, l.summaryRow, l.dance),
					read(sh, l.summaryRow, l.totalTtc),
					drinksTotal(sh, l),
					read(sh, l.paymentsRow, l.cash),
					read(sh, l.paymentsRow, l.card),
					read(sh, l.paymentsRow, l.cheque));
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

	/**
	 * The drinks block's "Total" moves when rows are added to the template (it was row 58 until
	 * April 2026, row 59 since). Find it by its label — a "Total" cell in the drinks columns with
	 * the amount to its right — and fall back to the layout cell only when no label is found.
	 */
	static double drinksTotal(Sheet sh, Layout l)
	{
		int labelCol = l.drinksCol - 1;
		int firstRow = Math.max(0, l.drinksRow - 15);
		int lastRow = l.drinksRow + 15;
		for (int r = firstRow; r <= lastRow; r++)
		{
			Row row = sh.getRow(r);
			if (row == null || labelCol < 0)
			{
				continue;
			}
			Cell label = row.getCell(labelCol);
			if (label != null && label.getCellType() == CellType.STRING
					&& "total".equalsIgnoreCase(label.getStringCellValue().trim()))
			{
				return read(sh, r, l.drinksCol);
			}
		}
		return read(sh, l.drinksRow, l.drinksCol);
	}

	/** Same rules as the desktop importer's readNumber: numbers, cached formula results, or text amounts. */
	static double read(Sheet sh, int row0, int col0)
	{
		Row r = sh.getRow(row0);
		if (r == null)
		{
			return 0.0;
		}
		Cell c = r.getCell(col0);
		if (c == null)
		{
			return 0.0;
		}
		try
		{
			CellType type = c.getCellType();
			if (type == CellType.NUMERIC)
			{
				return c.getNumericCellValue();
			}
			if (type == CellType.FORMULA)
			{
				CellType cached = c.getCachedFormulaResultType();
				if (cached == CellType.NUMERIC)
				{
					return c.getNumericCellValue();
				}
				if (cached == CellType.STRING)
				{
					return parseMoney(c.getStringCellValue());
				}
				return 0.0;
			}
			if (type == CellType.STRING)
			{
				return parseMoney(c.getStringCellValue());
			}
			return 0.0;
		}
		catch (RuntimeException e)
		{
			return 0.0;
		}
	}

	static double parseMoney(String s)
	{
		if (s == null)
		{
			return 0.0;
		}
		String cleaned = s.replaceAll("[^0-9,.-]", "");
		if (cleaned.contains(",") && cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.'))
		{
			cleaned = cleaned.replace(".", "").replace(',', '.');
		}
		else
		{
			cleaned = cleaned.replace(",", "");
		}
		try
		{
			return Double.parseDouble(cleaned);
		}
		catch (NumberFormatException e)
		{
			return 0.0;
		}
	}
}
