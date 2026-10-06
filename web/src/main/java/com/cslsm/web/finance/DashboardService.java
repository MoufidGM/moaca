package com.cslsm.web.finance;

import com.cslsm.web.dailylog.DailyLogRepository;
import com.cslsm.web.expenses.ExpenseModels.Totals;
import com.cslsm.web.expenses.ExpenseRepository;
import com.cslsm.web.finance.FinanceModels.BankAccount;
import com.cslsm.web.finance.FinanceModels.BankBalance;
import com.cslsm.web.finance.FinanceModels.FutureExpense;
import com.cslsm.web.finance.FinanceModels.NamedAmount;
import com.cslsm.web.finance.FinanceModels.ReceptionBalance;
import com.cslsm.web.finance.FinanceModels.SafeBalance;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DashboardService
{
	/** How far back the "missing log file" check looks. */
	private static final int MISSING_DAYS_WINDOW = 30;

	private final FinanceRepository finance;
	private final ExpenseRepository expenseRepo;
	private final DailyLogRepository dailyLogs;

	public DashboardService(FinanceRepository finance, ExpenseRepository expenseRepo, DailyLogRepository dailyLogs)
	{
		this.finance = finance;
		this.expenseRepo = expenseRepo;
		this.dailyLogs = dailyLogs;
	}

	/**
	 * Change vs the same days of the previous month. css is "good", "bad" or "flat" depending
	 * on whether a rise is desirable (income, profit) or not (expenses).
	 */
	public record Delta(String text, String css)
	{
		public static Delta of(double current, double previous, boolean higherIsBetter)
		{
			if (previous == 0)
			{
				return null;
			}
			double pct = (current - previous) / Math.abs(previous) * 100;
			if (Math.abs(pct) < 0.5)
			{
				return new Delta("±0%", "flat");
			}
			boolean good = (pct > 0) == higherIsBetter;
			return new Delta(String.format(Locale.ROOT, "%+.0f%%", pct), good ? "good" : "bad");
		}
	}

	/** One source of income: the center's departments, Tiki Taka, the Salon. */
	public record UnitRow(String name, String detail, double amount, double share, Delta delta)
	{
	}

	public record DashboardView(
			List<UnitRow> units,
			String periodLabel,
			String compareLabel,
			double income,
			double expenses,
			double net,
			Delta incomeDelta,
			Delta expensesDelta,
			Delta netDelta,
			ReceptionBalance reception,
			SafeBalance safe,
			double sentToBankThisYear,
			BankBalance associationBank,
			BankBalance restaurantBank,
			String chartSvg,
			List<LocalDate> missingDays,
			List<FutureExpense> futureExpenses,
			Totals pendingExpenses,
			Totals suspectedBankDeposits,
			int importsNeedingReview,
			List<NamedAmount> incomeByActivity,
			List<NamedAmount> topExpenseCategories,
			List<NamedAmount> paymentMix)
	{
		public boolean hasAttentionItems()
		{
			return !missingDays.isEmpty() || !futureExpenses.isEmpty() || pendingExpenses.count() > 0
					|| suspectedBankDeposits.count() > 0 || importsNeedingReview > 0;
		}
	}

	public DashboardView build(LocalDate today)
	{
		// This month so far vs the same days of last month — a fair comparison mid-month.
		LocalDate monthStart = today.withDayOfMonth(1);
		LocalDate prevStart = monthStart.minusMonths(1);
		LocalDate prevEnd = prevStart.withDayOfMonth(Math.min(today.getDayOfMonth(), prevStart.lengthOfMonth()));

		double income = finance.income(monthStart, today);
		double expenses = finance.expenses(monthStart, today);
		double prevIncome = finance.income(prevStart, prevEnd);
		double prevExpenses = finance.expenses(prevStart, prevEnd);

		return new DashboardView(
				units(monthStart, today, prevStart, prevEnd, income),
				range(monthStart, today),
				"vs " + range(prevStart, prevEnd),
				income,
				expenses,
				income - expenses,
				Delta.of(income, prevIncome, true),
				Delta.of(expenses, prevExpenses, false),
				Delta.of(income - expenses, prevIncome - prevExpenses, true),
				finance.receptionBalance(today),
				finance.safeBalance(),
				finance.sentToBankSince(today.withDayOfYear(1)),
				finance.bankBalance(BankAccount.ASSOCIATION),
				finance.bankBalance(BankAccount.RESTAURANT),
				chart(today),
				missingDays(today),
				finance.futureExpenses(today),
				expenseRepo.pending(),
				expenseRepo.suspectedBankDeposits(),
				dailyLogs.countNeedingReview(today.minusDays(60)),
				finance.incomeByActivity(monthStart, today),
				finance.topExpenseCategories(monthStart, today, 6),
				finance.paymentMix(monthStart, today));
	}

	/** The month's income split by where it was earned, each against the same days of last month. */
	private List<UnitRow> units(LocalDate from, LocalDate to, LocalDate prevFrom, LocalDate prevTo, double total)
	{
		double center = finance.dailyLogIncome(from, to);
		double restaurant = finance.restaurantSales(from, to);
		double salon = finance.salonSales(from, to);
		List<UnitRow> rows = new ArrayList<>();
		rows.add(new UnitRow("Sports center", "fields, subscriptions, park, shop, drinks — the daily log",
				center, total == 0 ? 0 : center / total, Delta.of(center, finance.dailyLogIncome(prevFrom, prevTo), true)));
		rows.add(new UnitRow("Tiki Taka", "restaurant sales, cash and card",
				restaurant, total == 0 ? 0 : restaurant / total, Delta.of(restaurant, finance.restaurantSales(prevFrom, prevTo), true)));
		rows.add(new UnitRow("Salon", "cash and card",
				salon, total == 0 ? 0 : salon / total, Delta.of(salon, finance.salonSales(prevFrom, prevTo), true)));
		return rows;
	}

	private String chart(LocalDate today)
	{
		YearMonth last = YearMonth.from(today);
		YearMonth first = last.minusMonths(11);
		LocalDate from = first.atDay(1);
		Map<YearMonth, Double> income = finance.monthlyIncome(from, today);
		Map<YearMonth, Double> expenses = finance.monthlyExpenses(from, today);

		List<MonthlyChartSvg.Point> points = new ArrayList<>();
		for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1))
		{
			points.add(new MonthlyChartSvg.Point(m, income.getOrDefault(m, 0.0), expenses.getOrDefault(m, 0.0)));
		}
		return MonthlyChartSvg.render(points);
	}

	/** Days in the last 30 (excluding today) with no imported log file. */
	private List<LocalDate> missingDays(LocalDate today)
	{
		LocalDate end = today.minusDays(1);
		LocalDate start = today.minusDays(MISSING_DAYS_WINDOW);
		LocalDate first = finance.firstLogDate().orElse(null);
		if (first == null)
		{
			return List.of();
		}
		if (first.isAfter(start))
		{
			start = first;
		}
		Set<LocalDate> logged = finance.loggedDates(start, end);
		List<LocalDate> missing = new ArrayList<>();
		for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1))
		{
			if (!logged.contains(d))
			{
				missing.add(d);
			}
		}
		return missing;
	}

	/** "1–29 Sep" or "28 Aug – 3 Sep". */
	public static String range(LocalDate from, LocalDate to)
	{
		String toMonth = to.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
		if (from.getMonth() == to.getMonth() && from.getYear() == to.getYear())
		{
			return from.getDayOfMonth() == to.getDayOfMonth()
					? to.getDayOfMonth() + " " + toMonth
					: from.getDayOfMonth() + "–" + to.getDayOfMonth() + " " + toMonth;
		}
		String fromMonth = from.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
		return from.getDayOfMonth() + " " + fromMonth + " – " + to.getDayOfMonth() + " " + toMonth;
	}
}
