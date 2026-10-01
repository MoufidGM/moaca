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
import java.util.ArrayList;
import java.util.List;
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
		/** Optional (−1 when absent): the family's consumption and the till's expenses of the day. */
		final int onAccountRow, onAccountCol, tillExpensesRow, tillExpensesCol;
		final int familyFirstRow, familyLastRow, familyNameCol, familyAmountCol, familyNoteCol;
		final int expensesFirstRow, expensesLastRow, expensesObjectCol, expensesCategoryCol, expensesAmountCol;

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
			onAccountRow = i(p, "onAccount.row", 0) - 1;
			onAccountCol = i(p, "onAccount.col", 0) - 1;
			tillExpensesRow = i(p, "tillExpenses.row", 0) - 1;
			tillExpensesCol = i(p, "tillExpenses.col", 0) - 1;
			familyFirstRow = i(p, "family.firstRow", 0) - 1;
			familyLastRow = i(p, "family.lastRow", 0) - 1;
			familyNameCol = i(p, "family.nameCol", 0) - 1;
			familyAmountCol = i(p, "family.amountCol", 0) - 1;
			familyNoteCol = i(p, "family.noteCol", 0) - 1;
			expensesFirstRow = i(p, "expenses.firstRow", 0) - 1;
			expensesLastRow = i(p, "expenses.lastRow", 0) - 1;
			expensesObjectCol = i(p, "expenses.objectCol", 0) - 1;
			expensesCategoryCol = i(p, "expenses.categoryCol", 0) - 1;
			expensesAmountCol = i(p, "expenses.amountCol", 0) - 1;
		}

		boolean hasFamily()
		{
			return familyFirstRow >= 0 && familyNameCol >= 0 && familyAmountCol >= 0;
		}

		boolean hasExpenses()
		{
			return expensesFirstRow >= 0 && expensesObjectCol >= 0 && expensesAmountCol >= 0;
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

	/** A family member's meal, not paid, tracked per person. */
	public record FamilyLine(int rowNumber, String member, double amount, String note)
	{
	}

	/** An expense paid out of the till that day. */
	public record ExpenseLine(int rowNumber, String object, String category, double amount)
	{
	}

	/**
	 * The figures of one day. total is the day's sales at menu value, the family's meals
	 * included; cash is what is left once card, the family's meals and the till's expenses
	 * are taken out.
	 */
	public record ParsedUnitDay(String unit, LocalDate date, double total, double card, double onAccount, double tillExpenses,
								Integer people, String note, List<FamilyLine> family, List<ExpenseLine> expenses)
	{
		public ParsedUnitDay(String unit, LocalDate date, double total, double card, Integer people, String note)
		{
			this(unit, date, total, card, 0, 0, people, note, List.of(), List.of());
		}

		/** Cash sales: the day's sales minus card and the family's meals (the till's expenses are recorded as expenses). */
		public double cash()
		{
			return Math.round((total - card - onAccount) * 100) / 100.0;
		}

		/** What should be in the till at closing: cash sales minus what the till paid out. */
		public double cashLeft()
		{
			return Math.round((cash() - tillExpenses) * 100) / 100.0;
		}

		public boolean isEmpty()
		{
			return total == 0 && card == 0 && family.isEmpty() && expenses.isEmpty();
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
			List<FamilyLine> family = new ArrayList<>();
			if (layout.hasFamily())
			{
				for (int r = layout.familyFirstRow; r <= layout.familyLastRow; r++)
				{
					String member = text(sh, r, layout.familyNameCol);
					double amount = DailyLogParser.read(sh, r, layout.familyAmountCol);
					if (member == null && amount == 0)
					{
						continue;
					}
					family.add(new FamilyLine(r + 1, member, amount, layout.familyNoteCol < 0 ? null : text(sh, r, layout.familyNoteCol)));
				}
			}
			List<ExpenseLine> expenses = new ArrayList<>();
			if (layout.hasExpenses())
			{
				for (int r = layout.expensesFirstRow; r <= layout.expensesLastRow; r++)
				{
					String object = text(sh, r, layout.expensesObjectCol);
					double amount = DailyLogParser.read(sh, r, layout.expensesAmountCol);
					if (object == null && amount == 0)
					{
						continue;
					}
					expenses.add(new ExpenseLine(r + 1, object, layout.expensesCategoryCol < 0 ? null : text(sh, r, layout.expensesCategoryCol), amount));
				}
			}
			// The summary's own figures for the two blocks, when the file has them; else the lines' sums
			double onAccount = layout.onAccountRow >= 0 ? DailyLogParser.read(sh, layout.onAccountRow, layout.onAccountCol)
					: family.stream().mapToDouble(FamilyLine::amount).sum();
			double tillExpenses = layout.tillExpensesRow >= 0 ? DailyLogParser.read(sh, layout.tillExpensesRow, layout.tillExpensesCol)
					: expenses.stream().mapToDouble(ExpenseLine::amount).sum();
			return new ParsedUnitDay(layout.unit, date, total, card, onAccount, tillExpenses,
					people <= 0 ? null : (int) Math.round(people), note, List.copyOf(family), List.copyOf(expenses));
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
		Cell c = r == null || col0 < 0 ? null : r.getCell(col0);
		if (c == null)
		{
			return null;
		}
		String s;
		if (c.getCellType() == CellType.STRING)
		{
			s = c.getStringCellValue();
		}
		else if (c.getCellType() == CellType.NUMERIC)
		{
			s = new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(c);
		}
		else
		{
			return null;
		}
		s = s.trim();
		return s.isEmpty() ? null : (s.length() > 200 ? s.substring(0, 200) : s);
	}
}
