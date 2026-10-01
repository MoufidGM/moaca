package com.cslsm.web.finance;

import com.cslsm.web.finance.FinanceModels.DayRow;
import com.cslsm.web.finance.FinanceModels.DaySummary;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the reception sees: the latest imported day, this week so far, and which of the last
 * seven days still have no log file. No reserves, no expenses totals, no profit.
 */
@Service
public class TodayService
{
	private final FinanceRepository finance;

	public TodayService(FinanceRepository finance)
	{
		this.finance = finance;
	}

	public record TodayView(Optional<DaySummary> latestDay,
							String weekLabel,
							double weekTotal,
							List<DayRow> lastSevenDays)
	{
		public long missingCount()
		{
			return lastSevenDays.stream().filter(DayRow::missing).count();
		}
	}

	public TodayView build(LocalDate today)
	{
		LocalDate monday = today.with(DayOfWeek.MONDAY);
		double weekTotal = finance.dailyLogIncome(monday, today);

		LocalDate from = today.minusDays(6);
		Map<LocalDate, Double> totals = finance.dailyTotals(from, today);
		List<DayRow> days = new ArrayList<>();
		for (LocalDate d = today; !d.isBefore(from); d = d.minusDays(1))
		{
			days.add(new DayRow(d, totals.get(d)));
		}

		return new TodayView(finance.latestDay(), DashboardService.range(monday, today), weekTotal, days);
	}
}
