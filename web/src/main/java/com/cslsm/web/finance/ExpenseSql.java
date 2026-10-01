package com.cslsm.web.finance;

/**
 * SQL fragments shared by every query over the expense table, so the dashboard, the expense
 * list, the balances and the activity analysis all apply the same rules.
 */
public final class ExpenseSql
{
	/** Rejected expenses (including misfiled bank deposits) never count anywhere. */
	public static final String COUNTS = "status <> 'REJECTED'";

	/** paid_from, or the desktop app's older paid_from_storage flag on rows that have no paid_from. */
	private static final String EFFECTIVE = "COALESCE(paid_from, CASE WHEN paid_from_storage = 1 THEN 'RECEPTION' ELSE 'BANK' END)";

	/**
	 * Where the money came from: RECEPTION, RESTAURANT (the restaurant's till), SAFE, BANK (the
	 * Association's account) or RESTAURANT_BANK (Tiki Taka's account). The till column tells
	 * the restaurant's till and account apart from the reception's and the Association's:
	 * RECEPTION + till RESTAURANT is the restaurant's till, BANK + till RESTAURANT its account.
	 */
	public static final String PAID_FROM =
			"CASE WHEN " + EFFECTIVE + " = 'RECEPTION' AND till = 'RESTAURANT' THEN 'RESTAURANT'"
					+ " WHEN " + EFFECTIVE + " = 'BANK' AND till = 'RESTAURANT' THEN 'RESTAURANT_BANK'"
					+ " ELSE " + EFFECTIVE + " END";

	/** Descriptions that suggest a bank deposit was recorded as an expense. */
	public static final String LOOKS_LIKE_BANK_DEPOSIT =
			"(lower(description) LIKE '%versement%' OR lower(description) LIKE '%au compte%'"
					+ " OR lower(description) LIKE '%banque%' OR lower(description) LIKE '%bank%'"
					+ " OR lower(description) LIKE '%depot%')";

	private ExpenseSql()
	{
	}

	/** PAID_FROM with every column prefixed by a table alias, e.g. "e.". */
	public static String paidFrom(String alias)
	{
		return PAID_FROM.replace("paid_from_storage", alias + "paid_from_storage")
				.replace(" paid_from,", " " + alias + "paid_from,")
				.replace("(paid_from,", "(" + alias + "paid_from,")
				.replace("till =", alias + "till =");
	}
}
