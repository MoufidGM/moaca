package com.cslsm.model;

public class CashMovement
{
	public static final String DEPOSIT = "DEPOSIT";
	public static final String WITHDRAWAL = "WITHDRAWAL";
	public static final String BANK = "BANK"; // transfer from storage to the bank (outflow)

	private Long id;
	private String movementDate; // yyyy-MM-dd
	private String type;         // DEPOSIT / WITHDRAWAL
	private Double amount;
	private String note;

	public Long getId()
	{
		return id;
	}

	public void setId(Long id)
	{
		this.id = id;
	}

	public String getMovementDate()
	{
		return movementDate;
	}

	public void setMovementDate(String movementDate)
	{
		this.movementDate = movementDate;
	}

	public String getType()
	{
		return type;
	}

	public void setType(String type)
	{
		this.type = type;
	}

	public Double getAmount()
	{
		return amount;
	}

	public void setAmount(Double amount)
	{
		this.amount = amount;
	}

	public String getNote()
	{
		return note;
	}

	public void setNote(String note)
	{
		this.note = note;
	}
}
