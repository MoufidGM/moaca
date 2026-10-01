package com.cslsm.web;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Builds small Excel files for tests, laid out like the real daily log template. */
public final class TestWorkbooks
{
	private TestWorkbooks()
	{
	}

	/**
	 * A daily log with the figures in the template's cells (Excel coordinates):
	 * row 10 = department totals (A terrain, G gym, AC total), row 14 = payments
	 * (A cash, M card, S cheque), drinks "Total" label in AD with the amount in AE.
	 */
	public static byte[] dailyLog(double terrain, double gym, double total, double cash, double card, double cheque,
								  double drinks, int drinksRow)
	{
		try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream())
		{
			Sheet s = wb.createSheet("Daily log");
			text(s, 9, 1, "TERRAIN");
			text(s, 9, 29, "TOTAL TTC");
			number(s, 10, 1, terrain);
			number(s, 10, 7, gym);
			number(s, 10, 29, total);
			number(s, 14, 1, cash);
			number(s, 14, 13, card);
			number(s, 14, 19, cheque);
			text(s, drinksRow, 30, "Total");
			number(s, drinksRow, 31, drinks);
			wb.write(out);
			return out.toByteArray();
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/** An expense sheet: header row, then one row per String[] (A..I). */
	public static byte[] expenseSheet(Object[]... rows)
	{
		try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream())
		{
			Sheet s = wb.createSheet("Expenses");
			String[] header = {"Date", "Category", "Description", "Amount", "Payment", "Entered by", "Approved by", "Activity", "Paid from"};
			for (int c = 0; c < header.length; c++)
			{
				text(s, 1, c + 1, header[c]);
			}
			for (int r = 0; r < rows.length; r++)
			{
				for (int c = 0; c < rows[r].length; c++)
				{
					Object v = rows[r][c];
					if (v instanceof Number n)
					{
						number(s, r + 2, c + 1, n.doubleValue());
					}
					else if (v != null)
					{
						text(s, r + 2, c + 1, v.toString());
					}
				}
			}
			wb.write(out);
			return out.toByteArray();
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static Row row(Sheet s, int excelRow)
	{
		Row r = s.getRow(excelRow - 1);
		return r != null ? r : s.createRow(excelRow - 1);
	}

	private static void number(Sheet s, int excelRow, int excelCol, double v)
	{
		row(s, excelRow).createCell(excelCol - 1).setCellValue(v);
	}

	private static void text(Sheet s, int excelRow, int excelCol, String v)
	{
		row(s, excelRow).createCell(excelCol - 1).setCellValue(v);
	}
}
