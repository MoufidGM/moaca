package com.cslsm.ui;

import javafx.application.HostServices;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;

public class MainView extends BorderPane
{
	private final TabPane tabs = new TabPane();

	public MainView(HostServices host)
	{
		Tab tabDaily = this.getTab("Daily", new DailyView(host));
		Tab tabWeekly = this.getTab("Weekly", new WeeklyView());
		Tab tabMonthly = this.getTab("Monthly", new MonthlyView());
		Tab tabYearly = this.getTab("Yearly", new YearlyView());
		Tab tabCustom = this.getTab("Custom", new CustomPeriodView());
		Tab tabExpenses = this.getTab("Expenses", new ExpensesView());
		Tab tabAnalysis = this.analysisTab(host);


		for (Tab t : new Tab[]{tabDaily, tabWeekly, tabMonthly, tabYearly, tabCustom, tabExpenses, tabAnalysis})
		{
			t.setClosable(false);
		}

		tabs.getTabs().addAll(tabDaily, tabWeekly, tabMonthly, tabYearly, tabCustom, tabExpenses, tabAnalysis);

		// Global storage bar, always visible on top of every view
		StorageBar storageBar = new StorageBar();
		setTop(storageBar);
		tabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> StorageBar.refreshActive());

		setCenter(tabs);
	}

	// NEW: parent "Analysis" tab containing its own TabPane with 3 sub-tabs
	private Tab analysisTab(HostServices host)
	{
		Tab analysis = new Tab("Analysis");
		analysis.setClosable(false);

		TabPane inner = new TabPane();
		inner.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
		inner.setSide(Side.TOP); // or Side.LEFT if you prefer vertical subtabs

		// OPTION 1: if you have view classes (JavaFX Nodes) like TrendsView, CompareDaysView, etc.:
		Node trendsView = new ActivityTrendsView();        // replace with your ctor/signature
		Node compareDaysView = new CompareDaysView(host);
		Node compareMonthsView = new CompareMonthsView();

		inner.getTabs().addAll(new Tab("Trends", trendsView), new Tab("Compare Days", compareDaysView), new Tab("Compare Months", compareMonthsView), new Tab("Payments Analysis", new PaymentsAnalysisView()), new Tab("Profit & Loss", new ProfitLossView()));

		analysis.setContent(inner);
		return analysis;
	}


	/**
	 * @param strName
	 * @param pane
	 * @return
	 */
	protected Tab getTab(String strName, BorderPane pane)
	{
		Tab tab = null;
		try
		{
			tab = new Tab(strName, pane);
		}
		catch (Exception e)
		{

		}
		return tab;
	}
}

