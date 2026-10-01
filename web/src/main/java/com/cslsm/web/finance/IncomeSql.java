package com.cslsm.web.finance;

/**
 * What counts as the center's income for a day.
 *
 * The daily log's own total (total_ttc) covers the departments only; drinks sold at the bar
 * are listed separately in the file. Drinks are center income, so a day's income is both.
 */
public final class IncomeSql
{
	/** Income of one daily_summary row: departments total + drinks. */
	public static final String DAY_INCOME = "(COALESCE(total_ttc, 0) + COALESCE(drinks_amount_total, 0))";

	private IncomeSql()
	{
	}
}
