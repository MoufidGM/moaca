package com.cslsm.web.income;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * The period shown on the Income page: a day, a week (Monday to Sunday), a month, a year or a
 * custom range. A period that is still running (this month, this year) is compared with the
 * same number of days of the previous one; a finished period with the whole previous one.
 * A day is compared with the same weekday a week earlier, which means more to a sports
 * center than "yesterday".
 *
 * @param from         first day of the period
 * @param to           last day queried: the period's last day, or today while it is running
 * @param end          the period's last day (may be after today)
 * @param prevAnchor   a date inside the previous period (null for a custom range)
 * @param nextAnchor   a date inside the next period, null when that period has not started
 */
public record IncomePeriod(Unit unit, LocalDate from, LocalDate to, LocalDate end, String label,
						   LocalDate compareFrom, LocalDate compareTo, String compareLabel,
						   LocalDate prevAnchor, LocalDate nextAnchor)
{
	public enum Unit
	{
		DAY("day", "Day"), WEEK("week", "Week"), MONTH("month", "Month"), YEAR("year", "Year"), CUSTOM("custom", "Custom");

		public final String key;
		public final String label;

		Unit(String key, String label)
		{
			this.key = key;
			this.label = label;
		}

		public String getKey()
		{
			return key;
		}

		public String getLabel()
		{
			return label;
		}

		static Unit of(String key)
		{
			for (Unit u : values())
			{
				if (u.key.equals(key))
				{
					return u;
				}
			}
			return MONTH;
		}
	}

	/** Custom ranges are capped so a typo cannot ask for a century. */
	private static final int MAX_CUSTOM_DAYS = 366 * 5;
	private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);
	private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
	private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

	public static IncomePeriod resolve(String unitKey, String dateText, String fromText, String toText, LocalDate today)
	{
		Unit unit = Unit.of(unitKey);
		LocalDate anchor = parse(dateText, today);
		if (anchor.isAfter(today))
		{
			anchor = today;
		}
		switch (unit)
		{
			case DAY:
				return day(anchor, today);
			case WEEK:
				return week(anchor, today);
			case YEAR:
				return year(anchor, today);
			case CUSTOM:
				LocalDate from = parse(fromText, null);
				LocalDate to = parse(toText, null);
				if (from != null && to != null && !from.isAfter(to) && ChronoUnit.DAYS.between(from, to) < MAX_CUSTOM_DAYS)
				{
					return custom(from, to, today);
				}
				return month(anchor, today);
			default:
				return month(anchor, today);
		}
	}

	private static IncomePeriod day(LocalDate d, LocalDate today)
	{
		LocalDate compare = d.minusWeeks(1);
		return new IncomePeriod(Unit.DAY, d, d, d, DAY_LABEL.format(d), compare, compare, "vs " + SHORT.format(compare),
				d.minusDays(1), d.isBefore(today) ? d.plusDays(1) : null);
	}

	private static IncomePeriod week(LocalDate anchor, LocalDate today)
	{
		LocalDate from = anchor.with(DayOfWeek.MONDAY);
		LocalDate end = from.plusDays(6);
		LocalDate to = end.isAfter(today) ? today : end;
		return new IncomePeriod(Unit.WEEK, from, to, end, range(from, end), from.minusWeeks(1), to.minusWeeks(1),
				"vs " + range(from.minusWeeks(1), to.minusWeeks(1)), from.minusWeeks(1), end.isBefore(today) ? from.plusWeeks(1) : null);
	}

	private static IncomePeriod month(LocalDate anchor, LocalDate today)
	{
		YearMonth ym = YearMonth.from(anchor);
		LocalDate from = ym.atDay(1);
		LocalDate end = ym.atEndOfMonth();
		LocalDate to = end.isAfter(today) ? today : end;
		YearMonth prev = ym.minusMonths(1);
		LocalDate compareTo = to.equals(end) ? prev.atEndOfMonth() : prev.atDay(Math.min(to.getDayOfMonth(), prev.lengthOfMonth()));
		return new IncomePeriod(Unit.MONTH, from, to, end, MONTH_LABEL.format(ym), prev.atDay(1), compareTo,
				"vs " + (to.equals(end) ? MONTH_LABEL.format(prev) : range(prev.atDay(1), compareTo)),
				prev.atDay(1), end.isBefore(today) ? ym.plusMonths(1).atDay(1) : null);
	}

	private static IncomePeriod year(LocalDate anchor, LocalDate today)
	{
		LocalDate from = anchor.withDayOfYear(1);
		LocalDate end = from.plusYears(1).minusDays(1);
		LocalDate to = end.isAfter(today) ? today : end;
		LocalDate compareTo = to.equals(end) ? end.minusYears(1) : to.minusYears(1);
		return new IncomePeriod(Unit.YEAR, from, to, end, String.valueOf(from.getYear()), from.minusYears(1), compareTo,
				"vs " + (to.equals(end) ? String.valueOf(from.getYear() - 1) : range(from.minusYears(1), compareTo)),
				from.minusYears(1), end.isBefore(today) ? from.plusYears(1) : null);
	}

	private static IncomePeriod custom(LocalDate from, LocalDate requestedTo, LocalDate today)
	{
		LocalDate end = requestedTo;
		LocalDate to = end.isAfter(today) ? today : end;
		if (to.isBefore(from))
		{
			to = from;
		}
		long days = ChronoUnit.DAYS.between(from, to) + 1;
		LocalDate compareTo = from.minusDays(1);
		LocalDate compareFrom = compareTo.minusDays(days - 1);
		return new IncomePeriod(Unit.CUSTOM, from, to, end, range(from, end), compareFrom, compareTo,
				"vs " + range(compareFrom, compareTo), null, null);
	}

	/** Days of the period that are in the past or today. */
	public long days()
	{
		return ChronoUnit.DAYS.between(from, to) + 1;
	}

	/** Whether the period continues after today. */
	public boolean running()
	{
		return end.isAfter(to);
	}

	/** Buckets: one per day for up to two months, one per month beyond. */
	public boolean monthly()
	{
		return ChronoUnit.DAYS.between(from, end) >= 62;
	}

	public boolean isCustom()
	{
		return unit == Unit.CUSTOM;
	}

	/** "1 – 15 Sep 2026", "28 Sep – 4 Oct 2026", "20 Dec 2025 – 5 Jan 2026". */
	static String range(LocalDate from, LocalDate to)
	{
		if (from.equals(to))
		{
			return DATE.format(from);
		}
		if (from.getYear() != to.getYear())
		{
			return DATE.format(from) + " – " + DATE.format(to);
		}
		if (from.getMonth() != to.getMonth())
		{
			return from.getDayOfMonth() + " " + from.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)
					+ " – " + DATE.format(to);
		}
		return from.getDayOfMonth() + " – " + DATE.format(to);
	}

	private static LocalDate parse(String text, LocalDate fallback)
	{
		if (text == null || text.isBlank())
		{
			return fallback;
		}
		try
		{
			return LocalDate.parse(text.trim());
		}
		catch (DateTimeParseException e)
		{
			return fallback;
		}
	}
}
