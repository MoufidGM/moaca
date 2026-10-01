package com.cslsm.web.expenses;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

public final class ExpenseModels
{
	private ExpenseModels()
	{
	}

	public static final List<String> PAYMENT_METHODS = List.of("CASH", "CARD", "CHEQUE", "TRANSFER");
	/**
	 * Effective "money came from" values. RESTAURANT (the restaurant's till) is stored as
	 * RECEPTION + till RESTAURANT, and RESTAURANT_BANK (Tiki Taka's bank account) as BANK + till
	 * RESTAURANT; BANK on its own is the Association's account.
	 */
	public static final List<String> PAID_FROM = List.of("RECEPTION", "RESTAURANT", "SAFE", "BANK", "RESTAURANT_BANK");

	public static String paidFromLabel(String code)
	{
		if (code == null)
		{
			return "";
		}
		return switch (code)
		{
			case "RECEPTION" -> "Reception till";
			case "RESTAURANT" -> "Restaurant till";
			case "SAFE" -> "Safe";
			case "BANK" -> "Association bank account";
			case "RESTAURANT_BANK" -> "Tiki Taka bank account";
			default -> code;
		};
	}

	/** Column value for paid_from: the restaurant's till and account are RECEPTION / BANK with till RESTAURANT. */
	public static String storedPaidFrom(String effective)
	{
		return switch (effective)
		{
			case "RESTAURANT" -> "RECEPTION";
			case "RESTAURANT_BANK" -> "BANK";
			default -> effective;
		};
	}

	public static String storedTill(String effective)
	{
		return "RESTAURANT".equals(effective) || "RESTAURANT_BANK".equals(effective) ? "RESTAURANT" : "RECEPTION";
	}

	/** Paid from a bank account (either one), so it never was cash in a till. */
	public static boolean isBank(String effective)
	{
		return "BANK".equals(effective) || "RESTAURANT_BANK".equals(effective);
	}

	public static String paymentLabel(String code)
	{
		if (code == null)
		{
			return "";
		}
		return switch (code)
		{
			case "CASH" -> "Cash";
			case "CARD" -> "Card";
			case "CHEQUE" -> "Cheque";
			case "TRANSFER" -> "Transfer";
			default -> code;
		};
	}

	/**
	 * A choice from expense_option. costRule / incomeColumn / partOf apply to activities (V15);
	 * heading (SALARIES, MAINTENANCE, PURCHASES, UTILITIES, OTHER) applies to categories (V16).
	 */
	public record Option(long id, String kind, String name, boolean adminOnly, boolean active,
						 String costRule, String incomeColumn, String partOf, String heading)
	{
		public boolean isSalaries()
		{
			return "SALARIES".equals(heading);
		}

		public boolean isShared()
		{
			return "SHARED".equals(costRule);
		}

		public boolean isRevenue()
		{
			return "REVENUE".equals(costRule);
		}
	}

	/** One part of a split expense. */
	public record Split(String activity, double percent)
	{
	}

	/** One expense as shown in lists and on the detail page. */
	public record ExpenseRow(long id, LocalDate date, String category, String description, double amount,
							 String paymentMethod, String activity, String paidFrom, String status,
							 String enteredBy, String approvedBy, String approvedAt, String rejectionReason,
							 Long createdByUserId, String createdAt, int attachmentCount, int splitCount,
							 Long employeeId, String employeeName)
	{
		public boolean isSplit()
		{
			return splitCount > 0;
		}

		public boolean isPending()
		{
			return "PENDING".equals(status);
		}

		public boolean isRejected()
		{
			return "REJECTED".equals(status);
		}

		public String statusLabel()
		{
			return switch (status)
			{
				case "PENDING" -> "Pending";
				case "REJECTED" -> "Rejected";
				default -> "Approved";
			};
		}

		public String paidFromLabel()
		{
			return ExpenseModels.paidFromLabel(paidFrom);
		}

		public String paymentLabel()
		{
			return ExpenseModels.paymentLabel(paymentMethod);
		}

		/** Same rule as ExpenseSql.LOOKS_LIKE_BANK_DEPOSIT. */
		public boolean looksLikeBankDeposit()
		{
			if (description == null || isRejected())
			{
				return false;
			}
			String d = description.toLowerCase(Locale.ROOT);
			return d.contains("versement") || d.contains("au compte") || d.contains("banque")
					|| d.contains("bank") || d.contains("depot");
		}
	}

	/** What gets written to the expense table. */
	public record ExpenseDraft(LocalDate date, String category, String description, double amount,
							   String paymentMethod, String activity, String paidFrom, String status,
							   String enteredBy, String approvedBy, String approvedAt, Long createdByUserId,
							   Long employeeId)
	{
		public ExpenseDraft(LocalDate date, String category, String description, double amount,
							String paymentMethod, String activity, String paidFrom, String status,
							String enteredBy, String approvedBy, String approvedAt, Long createdByUserId)
		{
			this(date, category, description, amount, paymentMethod, activity, paidFrom, status,
					enteredBy, approvedBy, approvedAt, createdByUserId, null);
		}
	}

	/** The HTML form, bound field by field. Strings so bad input can be shown back unchanged. */
	public static class ExpenseForm
	{
		/** Rows shown in the "split across activities" section. */
		public static final int SPLIT_ROWS = 4;

		private String date;
		private String category;
		private String description;
		private String amount;
		private String paymentMethod = "CASH";
		private String activity = "General";
		private String paidFrom = "RECEPTION";
		private String employeeId = "";
		private List<String> splitActivity = new java.util.ArrayList<>();

		public String getEmployeeId() { return employeeId; }
		public void setEmployeeId(String employeeId) { this.employeeId = employeeId == null ? "" : employeeId; }
		private List<String> splitPercent = new java.util.ArrayList<>();

		public List<String> getSplitActivity() { return splitActivity; }
		public void setSplitActivity(List<String> splitActivity) { this.splitActivity = splitActivity == null ? new java.util.ArrayList<>() : splitActivity; }
		public List<String> getSplitPercent() { return splitPercent; }
		public void setSplitPercent(List<String> splitPercent) { this.splitPercent = splitPercent == null ? new java.util.ArrayList<>() : splitPercent; }

		/** The split rows padded to SPLIT_ROWS, each {activity, percent}, for the template. */
		public List<String[]> getSplitRows()
		{
			List<String[]> rows = new java.util.ArrayList<>();
			for (int i = 0; i < Math.max(SPLIT_ROWS, splitActivity.size()); i++)
			{
				rows.add(new String[]{
						i < splitActivity.size() && splitActivity.get(i) != null ? splitActivity.get(i) : "",
						i < splitPercent.size() && splitPercent.get(i) != null ? splitPercent.get(i) : ""});
			}
			return rows;
		}

		public boolean hasSplit()
		{
			return splitActivity.stream().anyMatch(s -> s != null && !s.isBlank())
					|| splitPercent.stream().anyMatch(s -> s != null && !s.isBlank());
		}

		public String getDate() { return date; }
		public void setDate(String date) { this.date = date; }
		public String getCategory() { return category; }
		public void setCategory(String category) { this.category = category; }
		public String getDescription() { return description; }
		public void setDescription(String description) { this.description = description; }
		public String getAmount() { return amount; }
		public void setAmount(String amount) { this.amount = amount; }
		public String getPaymentMethod() { return paymentMethod; }
		public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
		public String getActivity() { return activity; }
		public void setActivity(String activity) { this.activity = activity; }
		public String getPaidFrom() { return paidFrom; }
		public void setPaidFrom(String paidFrom) { this.paidFrom = paidFrom; }

		public static ExpenseForm of(ExpenseRow row, List<Split> splits)
		{
			ExpenseForm f = new ExpenseForm();
			f.date = row.date().toString();
			f.category = row.category();
			f.description = row.description();
			f.amount = String.format(Locale.ROOT, "%.2f", row.amount()).replaceAll("\\.00$", "");
			f.paymentMethod = row.paymentMethod() == null ? "CASH" : row.paymentMethod();
			f.activity = row.activity();
			f.paidFrom = row.paidFrom();
			f.employeeId = row.employeeId() == null ? "" : String.valueOf(row.employeeId());
			for (Split s : splits)
			{
				f.splitActivity.add(s.activity());
				f.splitPercent.add(String.format(Locale.ROOT, "%.2f", s.percent()).replaceAll("\\.?0+$", ""));
			}
			return f;
		}
	}

	/** List filters from the query string. */
	public static class ExpenseQuery
	{
		private String month;
		private String status;
		private String category;
		private String activity;
		private String paidFrom;
		private String q;
		private boolean suspects;

		public String getMonth() { return month; }
		public void setMonth(String month) { this.month = month; }
		public String getStatus() { return status; }
		public void setStatus(String status) { this.status = status; }
		public String getCategory() { return category; }
		public void setCategory(String category) { this.category = category; }
		public String getActivity() { return activity; }
		public void setActivity(String activity) { this.activity = activity; }
		public String getPaidFrom() { return paidFrom; }
		public void setPaidFrom(String paidFrom) { this.paidFrom = paidFrom; }
		public String getQ() { return q; }
		public void setQ(String q) { this.q = q; }
		public boolean isSuspects() { return suspects; }
		public void setSuspects(boolean suspects) { this.suspects = suspects; }
	}

	/** Resolved search passed to the repository. */
	public record ExpenseSearch(LocalDate from, LocalDate to, String status, String category, String activity,
								String paidFrom, String text, Long createdBy, boolean bankSuspects)
	{
	}

	public record Totals(int count, double amount)
	{
	}

	/** A validation or permission problem to show to the user as-is. */
	public static class ExpenseRuleException extends RuntimeException
	{
		public ExpenseRuleException(String message)
		{
			super(message);
		}
	}
}
