package com.cslsm.web.expenses;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads an expense sheet — same columns as the desktop app's import and the template:
 *
 *   A Date | B Category | C Description | D Amount | E Payment | F Entered by | G Approved by
 *   H Activity | I From storage (YES = reception, NO = bank, SAFE = safe)
 *
 * Only reads; checking against the category/activity lists happens in ExpenseImportService.
 */
public final class ExpenseSheetParser
{
	public static final int MAX_ROWS = 2000;

	public static final class SheetRow
	{
		public final int rowNumber;
		public final LocalDate date;
		public final String dateText;
		public final String category;
		public final String description;
		public final Double amount;
		public final String payment;
		public final String enteredBy;
		public final String approvedBy;
		public final String activity;
		public final String fromStorage;

		SheetRow(int rowNumber, LocalDate date, String dateText, String category, String description, Double amount,
				 String payment, String enteredBy, String approvedBy, String activity, String fromStorage)
		{
			this.rowNumber = rowNumber;
			this.date = date;
			this.dateText = dateText;
			this.category = category;
			this.description = description;
			this.amount = amount;
			this.payment = payment;
			this.enteredBy = enteredBy;
			this.approvedBy = approvedBy;
			this.activity = activity;
			this.fromStorage = fromStorage;
		}
	}

	public static final class SheetException extends Exception
	{
		public SheetException(String message)
		{
			super(message);
		}
	}

	private static final DateTimeFormatter[] DATE_FORMATS = {
			DateTimeFormatter.ofPattern("yyyy-MM-dd"),
			DateTimeFormatter.ofPattern("dd-MM-yyyy"),
			DateTimeFormatter.ofPattern("dd/MM/yyyy"),
			DateTimeFormatter.ofPattern("d/M/yyyy"),
			DateTimeFormatter.ofPattern("d-M-yyyy")};

	private ExpenseSheetParser()
	{
	}

	public static List<SheetRow> parse(InputStream in) throws SheetException
	{
		List<SheetRow> rows = new ArrayList<>();
		try (Workbook wb = WorkbookFactory.create(in))
		{
			Sheet sheet = wb.getSheetAt(0);
			for (Row row : sheet)
			{
				String dateText = text(row.getCell(0));
				LocalDate date = date(row.getCell(0));
				Double amount = number(row.getCell(3));
				String category = text(row.getCell(1));
				String description = text(row.getCell(2));
				// Header, blank or note rows: nothing that looks like an expense
				if (date == null && amount == null && (dateText == null || !dateText.matches(".*\\d.*")))
				{
					continue;
				}
				if (date == null && amount == null && category == null && description == null)
				{
					continue;
				}
				rows.add(new SheetRow(row.getRowNum() + 1, date, dateText, category, description, amount,
						text(row.getCell(4)), text(row.getCell(5)), text(row.getCell(6)), text(row.getCell(7)),
						text(row.getCell(8))));
				if (rows.size() > MAX_ROWS)
				{
					throw new SheetException("The sheet has more than " + MAX_ROWS + " expense rows. Split it into smaller files.");
				}
			}
		}
		catch (SheetException e)
		{
			throw e;
		}
		catch (IOException | RuntimeException e)
		{
			throw new SheetException("This file could not be read as an Excel workbook.");
		}
		return rows;
	}

	static LocalDate date(Cell c)
	{
		if (c == null)
		{
			return null;
		}
		try
		{
			if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c))
			{
				return c.getLocalDateTimeCellValue().toLocalDate();
			}
			String s = text(c);
			if (s == null)
			{
				return null;
			}
			for (DateTimeFormatter f : DATE_FORMATS)
			{
				try
				{
					return LocalDate.parse(s, f);
				}
				catch (RuntimeException ignored)
				{
					// try the next format
				}
			}
		}
		catch (RuntimeException ignored)
		{
			// unreadable cell
		}
		return null;
	}

	static Double number(Cell c)
	{
		if (c == null)
		{
			return null;
		}
		try
		{
			if (c.getCellType() == CellType.NUMERIC)
			{
				return c.getNumericCellValue();
			}
			if (c.getCellType() == CellType.FORMULA && c.getCachedFormulaResultType() == CellType.NUMERIC)
			{
				return c.getNumericCellValue();
			}
			String s = text(c);
			return s == null ? null : com.cslsm.web.support.Money.parse(s);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	static String text(Cell c)
	{
		if (c == null)
		{
			return null;
		}
		String s;
		try
		{
			CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
			if (type == CellType.STRING)
			{
				s = c.getStringCellValue();
			}
			else if (type == CellType.NUMERIC)
			{
				double v = c.getNumericCellValue();
				s = v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
			}
			else if (type == CellType.BOOLEAN)
			{
				s = String.valueOf(c.getBooleanCellValue());
			}
			else
			{
				return null;
			}
		}
		catch (RuntimeException e)
		{
			return null;
		}
		s = s.trim().replaceAll("\\s+", " ");
		return s.isEmpty() ? null : s;
	}

	/* ---------------- spelling variants seen in the real data ---------------- */

	private static final Map<String, String> ACTIVITY_ALIASES = new HashMap<>();
	private static final Map<String, String> CATEGORY_ALIASES = new HashMap<>();

	static
	{
		for (String s : new String[]{"menage", "nettoyage", "femme de menage"})
		{
			ACTIVITY_ALIASES.put(s, "Cleaning");
		}
		for (String s : new String[]{"t-foot", "t foot", "tfoot", "terrains foot", "terrain foot", "foot", "football", "terrains"})
		{
			ACTIVITY_ALIASES.put(s, "Location de terrains");
		}
		for (String s : new String[]{"academie", "academy foot", "academie foot"})
		{
			ACTIVITY_ALIASES.put(s, "Académie");
		}
		for (String s : new String[]{"tournoi", "gala", "gala-match", "gala match", "match", "evenement", "event"})
		{
			ACTIVITY_ALIASES.put(s, "Events");
		}
		for (String s : new String[]{"technique", "tech"})
		{
			ACTIVITY_ALIASES.put(s, "Technical");
		}
		for (String s : new String[]{"reception", "generale", "general"})
		{
			ACTIVITY_ALIASES.put(s, "General");
		}
		ACTIVITY_ALIASES.put("taekwondo", "Arts Martiaux");
		ACTIVITY_ALIASES.put("teakwondo", "Arts Martiaux");
		ACTIVITY_ALIASES.put("terrain", "Location de terrains");
		ACTIVITY_ALIASES.put("academy", "Académie");
		ACTIVITY_ALIASES.put("arts martiaux", "Arts Martiaux");
		ACTIVITY_ALIASES.put("ping pong", "Ping Pong");
		ACTIVITY_ALIASES.put("mini-golf", "Mini Golf");
		ACTIVITY_ALIASES.put("chaussures", "Shoes");
		ACTIVITY_ALIASES.put("boissons", "Drinks");
		ACTIVITY_ALIASES.put("boisson", "Drinks");
		ACTIVITY_ALIASES.put("dance", "Danse");

		for (String s : new String[]{"elictricity", "electricite", "electricty"})
		{
			CATEGORY_ALIASES.put(s, "Electricity");
		}
		CATEGORY_ALIASES.put("eau", "Water");
		CATEGORY_ALIASES.put("salaire", "Salaries");
		CATEGORY_ALIASES.put("salaires", "Salaries");
		CATEGORY_ALIASES.put("salary", "Salaries");
		CATEGORY_ALIASES.put("avance", "Salary advance");
		CATEGORY_ALIASES.put("avance sur salaire", "Salary advance");
		CATEGORY_ALIASES.put("fournitures", "Supplies");
		CATEGORY_ALIASES.put("entretien", "Maintenance");
		CATEGORY_ALIASES.put("reparation", "Maintenance");
		CATEGORY_ALIASES.put("menage", "Cleaning");
		CATEGORY_ALIASES.put("nettoyage", "Cleaning");
		CATEGORY_ALIASES.put("loyer", "Rent");
		CATEGORY_ALIASES.put("assurance", "Insurance");
		CATEGORY_ALIASES.put("impots", "Taxes");
		CATEGORY_ALIASES.put("telephone", "Phone/Internet");
		CATEGORY_ALIASES.put("internet", "Phone/Internet");
	}

	/** Lower case, no accents, single spaces: "Ménage " -> "menage". */
	public static String normalize(String s)
	{
		if (s == null)
		{
			return "";
		}
		String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return n.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
	}

	public static String activityAlias(String raw)
	{
		return ACTIVITY_ALIASES.get(normalize(raw));
	}

	public static String categoryAlias(String raw)
	{
		return CATEGORY_ALIASES.get(normalize(raw));
	}
}
