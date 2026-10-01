package com.cslsm.model;

public class LineItem
{
	private Long id;
	private String logDate; // yyyy-MM-dd
	private String pointDeVente;
	private String prestation;
	private Integer quantite;
	private Double prix;
	private Double total;

		private String paymentMethod; // 'CASH' or 'CARD'

// Getters & setters
	public Long getId()
	{
		return id;
	}

	public void setId(Long id)
	{
		this.id = id;
	}

	public String getLogDate()
	{
		return logDate;
	}

	public void setLogDate(String logDate)
	{
		this.logDate = logDate;
	}

	public String getPointDeVente()
	{
		return pointDeVente;
	}

	public void setPointDeVente(String pointDeVente)
	{
		this.pointDeVente = pointDeVente;
	}

	public String getPrestation()
	{
		return prestation;
	}

	public void setPrestation(String prestation)
	{
		this.prestation = prestation;
	}

	public Integer getQuantite()
	{
		return quantite;
	}

	public void setQuantite(Integer quantite)
	{
		this.quantite = quantite;
	}

	public Double getPrix()
	{
		return prix;
	}

	public void setPrix(Double prix)
	{
		this.prix = prix;
	}

	public Double getTotal()
	{
		return total;
	}

	public void setTotal(Double total)
	{
		this.total = total;
	}

	public String getPaymentMethod()
	{
		return paymentMethod;
	}

	public void setPaymentMethod(String paymentMethod)
	{
		this.paymentMethod = paymentMethod;
	}
}

