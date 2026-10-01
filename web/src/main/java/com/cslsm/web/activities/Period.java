package com.cslsm.web.activities;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/** The period an analysis covers, chosen from presets or as a custom range. */
public record Period(String key, LocalDate from, LocalDate to, String label)
{
	public static final List<String[]> PRESETS = List.of(
			new String[]{"month", "This month"},
			new String[]{"last-month", "Last month"},
			new String[]{"3m", "Last 3 months"},
			new String[]{"year", "This year"},
			new String[]{"12m", "Last 12 months"});

	private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

	public static Period resolve(String key, String fromText, String toText, LocalDate today)
	{
		String k = key == null ? "year" : key;
		switch (k)
		{
			case "month":
				return of(k, today.withDayOfMonth(1), today);
			case "last-month":
				YearMonth last = YearMonth.from(today).minusMonths(1);
				return of(k, last.atDay(1), last.atEndOfMonth());
			case "3m":
				return of(k, YearMonth.from(today).minusMonths(2).atDay(1), today);
			case "12m":
				return of(k, YearMonth.from(today).minusMonths(11).atDay(1), today);
			case "custom":
				try
				{
					LocalDate from = LocalDate.parse(fromText);
					LocalDate to = LocalDate.parse(toText);
					if (to.isAfter(today))
					{
						to = today;
					}
					if (!from.isAfter(to) && ChronoUnit.DAYS.between(from, to) <= 366 * 5)
					{
						return of(k, from, to);
					}
				}
				catch (DateTimeParseException | NullPointerException ignored)
				{
					// fall through to the default
				}
				return of("year", today.withDayOfYear(1), today);
			default:
				return of("year", today.withDayOfYear(1), today);
		}
	}

	private static Period of(String key, LocalDate from, LocalDate to)
	{
		return new Period(key, from, to, LABEL.format(from) + " – " + LABEL.format(to));
	}

	/** The period of the same length just before this one, for comparison. */
	public Period previous()
	{
		long days = ChronoUnit.DAYS.between(from, to) + 1;
		LocalDate prevTo = from.minusDays(1);
		return of("previous", prevTo.minusDays(days - 1), prevTo);
	}

	public long days()
	{
		return ChronoUnit.DAYS.between(from, to) + 1;
	}
}
