package com.cslsm.web.income;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class IncomePeriodTest
{
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 30); // a Wednesday

	@Test
	void runningMonthComparesTheSameDaysOfLastMonth()
	{
		IncomePeriod p = IncomePeriod.resolve("month", "2026-09-10", null, null, TODAY);
		assertThat(p.from()).isEqualTo(LocalDate.of(2026, 9, 1));
		assertThat(p.to()).isEqualTo(TODAY);
		assertThat(p.end()).isEqualTo(TODAY);
		// The 30th is the month's last day, so the whole of August is the comparison
		assertThat(p.compareFrom()).isEqualTo(LocalDate.of(2026, 8, 1));
		assertThat(p.compareTo()).isEqualTo(LocalDate.of(2026, 8, 31));
		assertThat(p.compareLabel()).isEqualTo("vs August 2026");
		assertThat(p.nextAnchor()).isNull();
		assertThat(p.prevAnchor()).isEqualTo(LocalDate.of(2026, 8, 1));
		assertThat(p.running()).isFalse();

		IncomePeriod mid = IncomePeriod.resolve("month", null, null, null, LocalDate.of(2026, 9, 15));
		assertThat(mid.running()).isTrue();
		assertThat(mid.compareTo()).isEqualTo(LocalDate.of(2026, 8, 15));
		assertThat(mid.compareLabel()).isEqualTo("vs 1 – 15 Aug 2026");
	}

	@Test
	void finishedMonthComparesTheWholePreviousMonth()
	{
		IncomePeriod p = IncomePeriod.resolve("month", "2026-03-20", null, null, TODAY);
		assertThat(p.label()).isEqualTo("March 2026");
		assertThat(p.compareFrom()).isEqualTo(LocalDate.of(2026, 2, 1));
		assertThat(p.compareTo()).isEqualTo(LocalDate.of(2026, 2, 28));
		assertThat(p.compareLabel()).isEqualTo("vs February 2026");
		assertThat(p.nextAnchor()).isEqualTo(LocalDate.of(2026, 4, 1));
		assertThat(p.monthly()).isFalse();
	}

	@Test
	void weekRunsMondayToSundayAndDayComparesWithLastWeek()
	{
		IncomePeriod w = IncomePeriod.resolve("week", "2026-09-30", null, null, TODAY);
		assertThat(w.from()).isEqualTo(LocalDate.of(2026, 9, 28));
		assertThat(w.end()).isEqualTo(LocalDate.of(2026, 10, 4));
		assertThat(w.to()).isEqualTo(TODAY);
		assertThat(w.compareFrom()).isEqualTo(LocalDate.of(2026, 9, 21));
		assertThat(w.compareTo()).isEqualTo(LocalDate.of(2026, 9, 23));
		assertThat(w.label()).isEqualTo("28 Sep – 4 Oct 2026");

		IncomePeriod d = IncomePeriod.resolve("day", "2026-09-29", null, null, TODAY);
		assertThat(d.compareFrom()).isEqualTo(LocalDate.of(2026, 9, 22));
		assertThat(d.label()).isEqualTo("Tuesday 29 September 2026");
		assertThat(d.nextAnchor()).isEqualTo(TODAY);
	}

	@Test
	void yearAndCustomRanges()
	{
		IncomePeriod y = IncomePeriod.resolve("year", null, null, null, TODAY);
		assertThat(y.from()).isEqualTo(LocalDate.of(2026, 1, 1));
		assertThat(y.compareTo()).isEqualTo(LocalDate.of(2025, 9, 30));
		assertThat(y.monthly()).isTrue();
		assertThat(y.days()).isEqualTo(273);

		IncomePeriod c = IncomePeriod.resolve("custom", null, "2026-09-01", "2026-09-10", TODAY);
		assertThat(c.isCustom()).isTrue();
		assertThat(c.compareFrom()).isEqualTo(LocalDate.of(2026, 8, 22));
		assertThat(c.compareTo()).isEqualTo(LocalDate.of(2026, 8, 31));
		assertThat(c.prevAnchor()).isNull();

		// Future dates are capped at today; nonsense falls back to this month
		assertThat(IncomePeriod.resolve("day", "2030-01-01", null, null, TODAY).from()).isEqualTo(TODAY);
		assertThat(IncomePeriod.resolve("custom", null, "2026-09-10", "2026-09-01", TODAY).unit()).isEqualTo(IncomePeriod.Unit.MONTH);
		assertThat(IncomePeriod.resolve("bogus", "bogus", null, null, TODAY).unit()).isEqualTo(IncomePeriod.Unit.MONTH);
	}
}
