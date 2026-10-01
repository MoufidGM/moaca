package com.cslsm.model;

public class Expense
{
	/** Default categories offered in the UI (combo box stays editable, so new ones can be typed). */
	public static final String[] DEFAULT_CATEGORIES = {
			"Salaries", "Electricity", "Water", "Gas", "Heating",
			"Phone/Internet", "Maintenance", "Supplies", "Equipment",
			"Rent", "Insurance", "Taxes", "Other"
	};

	public static final String[] PAYMENT_METHODS = {"CASH", "CARD", "CHEQUE", "TRANSFER"};

	/** Activities an expense can be allocated to (aligned with the income departments). */
	public static final String[] ACTIVITIES = {
			"General", "Terrain", "Padel", "Gym", "Park", "Mini Golf",
			"Ping Pong", "Academy", "Taekwondo", "Shoes", "Drinks"
	};

	private Long id;
	private String expenseDate; // yyyy-MM-dd
	private String category;
	private String description;
	private Double amount;
	private String paymentMethod; // CASH / CARD / CHEQUE / TRANSFER
	private String enteredBy;
	private String approvedBy;
	private String activity;    // one of ACTIVITIES (or free text)
	private boolean paidFromStorage = true; // only these reduce the storage balance

	public Long getId()
	{
		return id;
	}

	public void setId(Long id)
	{
		this.id = id;
	}

	public String getExpenseDate()
	{
		return expenseDate;
	}

	public void setExpenseDate(String expenseDate)
	{
		this.expenseDate = expenseDate;
	}

	public String getCategory()
	{
		return category;
	}

	public void setCategory(String category)
	{
		this.category = category;
	}

	public String getDescription()
	{
		return description;
	}

	public void setDescription(String description)
	{
		this.description = description;
	}

	public Double getAmount()
	{
		return amount;
	}

	public void setAmount(Double amount)
	{
		this.amount = amount;
	}

	public String getPaymentMethod()
	{
		return paymentMethod;
	}

	public void setPaymentMethod(String paymentMethod)
	{
		this.paymentMethod = paymentMethod;
	}

	public String getEnteredBy()
	{
		return enteredBy;
	}

	public void setEnteredBy(String enteredBy)
	{
		this.enteredBy = enteredBy;
	}

	public String getApprovedBy()
	{
		return approvedBy;
	}

	public void setApprovedBy(String approvedBy)
	{
		this.approvedBy = approvedBy;
	}

	public String getActivity()
	{
		return activity;
	}

	public void setActivity(String activity)
	{
		this.activity = activity;
	}

	public boolean isPaidFromStorage()
	{
		return paidFromStorage;
	}

	public void setPaidFromStorage(boolean paidFromStorage)
	{
		this.paidFromStorage = paidFromStorage;
	}
}
