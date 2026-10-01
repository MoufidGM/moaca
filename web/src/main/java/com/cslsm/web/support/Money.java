package com.cslsm.web.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Parses amounts typed by people: "1250", "1 250,50", "1.250,50", "1,250.50", "250 dh".
 * A comma after the last dot is the decimal separator (French style), otherwise commas group
 * thousands — the same rule the daily-log importer has always used.
 */
public final class Money
{
	public static final double MAX_AMOUNT = 10_000_000;

	private Money()
	{
	}

	/** @return the amount rounded to 2 decimals, or null when the text is not a number */
	public static Double parse(String text)
	{
		if (text == null)
		{
			return null;
		}
		String cleaned = text.replaceAll("[^0-9,.-]", "");
		if (cleaned.isEmpty() || cleaned.equals("-"))
		{
			return null;
		}
		if (cleaned.matches("-?\\d{1,3}(,\\d{3})+"))
		{
			// "1,250" or "1,250,000": commas group thousands.
			cleaned = cleaned.replace(",", "");
		}
		else if (cleaned.contains(",") && cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.'))
		{
			// French style: dots group thousands, comma is the decimal separator ("1.250,50").
			cleaned = cleaned.replace(".", "").replace(',', '.');
		}
		else
		{
			cleaned = cleaned.replace(",", "");
		}
		if (cleaned.indexOf('.') != cleaned.lastIndexOf('.'))
		{
			return null; // "1.2.3"
		}
		try
		{
			return new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP).doubleValue();
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** An amount a person may enter: positive and below MAX_AMOUNT. */
	public static boolean isValidAmount(Double value)
	{
		return value != null && value > 0 && value <= MAX_AMOUNT;
	}
}
