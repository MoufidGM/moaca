package com.cslsm.ui;

import com.cslsm.model.DailySummary;
import com.cslsm.repo.DailyRepo;
import com.cslsm.repo.ExpenseRepo;
import com.cslsm.util.AppConfig;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.sql.SQLException;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.*;

/**
 * Profit & Loss: revenue vs expenses vs net per month for a year,
 * plus the expense category breakdown for a selected month.
 */
public class ProfitLossView extends BorderPane
{

	private final DailyRepo dailyRepo = new DailyRepo(AppConfig.getDbPath());
	private final ExpenseRepo expenseRepo = new ExpenseRepo(AppConfig.getDbPath());

	private final Label title = new Label("PROFIT & LOSS");
	private final ComboBox<Integer> cbYear = new ComboBox<>();
	private final ComboBox<Integer> cbMonth = new ComboBox<>();
	private final Button btnRefresh = new Button("Refresh");

	private final Label lblRevenue = new Label("Revenue: 0");
	private final Label lblExpenses = new Label("Expenses: 0");
	private final Label lblNet = new Label("NET: 0");

	private final BarChart<String, Number> chart;
	private final TableView<Map.Entry<String, Double>> catTable = new TableView<>();
	private final TableView<Map.Entry<String, Double>> actTable = new TableView<>();
	private final TableView<MonthRow> monthTable = new TableView<>();

	private final DecimalFormat INT = new DecimalFormat("#,##0");

	public ProfitLossView()
	{
		/* -------- Top -------- */
		title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));

		int nowYear = LocalDate.now().getYear();
		for (int y = nowYear - 5; y <= nowYear + 1; y++) cbYear.getItems().add(y);
		for (int m = 1; m <= 12; m++) cbMonth.getItems().add(m);
		cbYear.setValue(nowYear);
		cbMonth.setValue(LocalDate.now().getMonthValue());

		lblRevenue.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
		lblExpenses.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
		lblExpenses.setStyle("-fx-text-fill: #c0392b;");
		lblNet.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 22));

		Region spacerL = new Region();
		Region spacerR = new Region();
		HBox.setHgrow(spacerL, Priority.ALWAYS);
		HBox.setHgrow(spacerR, Priority.ALWAYS);

		HBox headerTotals = new HBox(20, lblRevenue, lblExpenses, lblNet);
		headerTotals.setAlignment(Pos.CENTER_RIGHT);

		HBox titleRow = new HBox(10, spacerL, title, spacerR, headerTotals);
		titleRow.setAlignment(Pos.CENTER);
		titleRow.setPadding(new Insets(8, 10, 0, 10));

		HBox ctrlRow = new HBox(12, new Label("Year:"), cbYear, new Label("Month:"), cbMonth, btnRefresh);
		ctrlRow.setPadding(new Insets(4, 10, 8, 10));
		ctrlRow.setAlignment(Pos.CENTER_LEFT);

		setTop(new VBox(titleRow, ctrlRow));

		/* -------- Center: chart + tables -------- */
		CategoryAxis x = new CategoryAxis();
		NumberAxis y = new NumberAxis();
		y.setTickLabelFormatter(new NumberAxis.DefaultFormatter(y)
		{
			@Override
			public String toString(Number n)
			{
				return INT.format(n.longValue());
			}
		});
		chart = new BarChart<>(x, y);
		chart.setAnimated(false);
		chart.setLegendVisible(true);
		chart.setStyle("-fx-font-size: 12px;");
		VBox.setVgrow(chart, Priority.ALWAYS);

		buildMonthTable();
		buildCatTable();

		Label monthsTitle = new Label("Months");
		monthsTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
		VBox leftBox = new VBox(6, monthsTitle, monthTable);
		VBox.setVgrow(monthTable, Priority.ALWAYS);
		HBox.setHgrow(leftBox, Priority.ALWAYS);

		Label catTitle = new Label("Selected month — expenses by category");
		catTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
		VBox rightBox = new VBox(6, catTitle, catTable);
		rightBox.setPrefWidth(300);
		VBox.setVgrow(catTable, Priority.ALWAYS);

		Label actTitle = new Label("Selected month — expenses by activity");
		actTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
		VBox actBox = new VBox(6, actTitle, actTable);
		actBox.setPrefWidth(300);
		VBox.setVgrow(actTable, Priority.ALWAYS);

		HBox tables = new HBox(10, leftBox, rightBox, actBox);
		VBox.setVgrow(tables, Priority.SOMETIMES);

		VBox center = new VBox(8, chart, tables);
		center.setPadding(new Insets(6, 10, 10, 10));
		setCenter(center);

		/* -------- events -------- */
		cbYear.valueProperty().addListener((o, a, b) -> reload());
		cbMonth.valueProperty().addListener((o, a, b) -> reload());
		btnRefresh.setOnAction(e -> reload());

		reload();
	}

	/* ===================== UI ===================== */

	private void buildMonthTable()
	{
		monthTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		monthTable.setStyle("-fx-font-size: 13px;");
		monthTable.setPlaceholder(new Label("No data."));

		TableColumn<MonthRow, String> cMonth = new TableColumn<>("Month");
		cMonth.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().label));

		TableColumn<MonthRow, String> cRev = new TableColumn<>("Revenue");
		cRev.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(INT.format(Math.round(cd.getValue().revenue))));
		cRev.setStyle("-fx-alignment: CENTER-RIGHT;");

		TableColumn<MonthRow, String> cExp = new TableColumn<>("Expenses");
		cExp.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(INT.format(Math.round(cd.getValue().expenses))));
		cExp.setStyle("-fx-alignment: CENTER-RIGHT;");

		TableColumn<MonthRow, String> cNet = new TableColumn<>("Net");
		cNet.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(INT.format(Math.round(cd.getValue().net()))));
		cNet.setStyle("-fx-alignment: CENTER-RIGHT;");
		cNet.setCellFactory(col -> new TableCell<>()
		{
			@Override
			protected void updateItem(String v, boolean empty)
			{
				super.updateItem(v, empty);
				if (empty)
				{
					setText("");
					setStyle("");
					return;
				}
				setText(v);
				MonthRow r = getTableView().getItems().get(getIndex());
				setStyle("-fx-alignment: CENTER-RIGHT; -fx-font-weight: 700; -fx-text-fill: " + (r.net() < 0 ? "#c0392b" : "#1e8e3e") + ";");
			}
		});

		monthTable.getColumns().setAll(List.of(cMonth, cRev, cExp, cNet));
	}

	private void buildCatTable()
	{
		catTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		catTable.setStyle("-fx-font-size: 13px;");
		catTable.setPlaceholder(new Label("No expenses this month."));

		TableColumn<Map.Entry<String, Double>, String> cCat = new TableColumn<>("Category");
		cCat.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().getKey()));

		TableColumn<Map.Entry<String, Double>, String> cTot = new TableColumn<>("Total");
		cTot.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(INT.format(Math.round(cd.getValue().getValue()))));
		cTot.setStyle("-fx-alignment: CENTER-RIGHT;");

		catTable.getColumns().setAll(List.of(cCat, cTot));

		actTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		actTable.setStyle("-fx-font-size: 13px;");
		actTable.setPlaceholder(new Label("No expenses this month."));

		TableColumn<Map.Entry<String, Double>, String> cAct = new TableColumn<>("Activity");
		cAct.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().getKey()));

		TableColumn<Map.Entry<String, Double>, String> cActTot = new TableColumn<>("Total");
		cActTot.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(INT.format(Math.round(cd.getValue().getValue()))));
		cActTot.setStyle("-fx-alignment: CENTER-RIGHT;");

		actTable.getColumns().setAll(List.of(cAct, cActTot));
	}

	/* ===================== Data ===================== */

	private void reload()
	{
		Integer y = cbYear.getValue();
		Integer m = cbMonth.getValue();
		if (y == null || m == null) return;

		title.setText("PROFIT & LOSS — " + y);

		XYChart.Series<String, Number> sRev = new XYChart.Series<>();
		sRev.setName("Revenue");
		XYChart.Series<String, Number> sExp = new XYChart.Series<>();
		sExp.setName("Expenses");
		XYChart.Series<String, Number> sNet = new XYChart.Series<>();
		sNet.setName("Net");

		List<MonthRow> rows = new ArrayList<>();
		for (int mm = 1; mm <= 12; mm++)
		{
			YearMonth ym = YearMonth.of(y, mm);
			double revenue = revenueOf(ym);
			double expenses = expenseRepo.sumBetween(ym.atDay(1), ym.atEndOfMonth());
			if (revenue == 0 && expenses == 0 && ym.isAfter(YearMonth.now())) continue;

			String label = ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
			rows.add(new MonthRow(label, revenue, expenses));
			sRev.getData().add(new XYChart.Data<>(label, revenue));
			sExp.getData().add(new XYChart.Data<>(label, expenses));
			sNet.getData().add(new XYChart.Data<>(label, revenue - expenses));
		}

		chart.getData().setAll(List.of(sRev, sExp, sNet));
		monthTable.getItems().setAll(rows);

		// selected month detail
		YearMonth sel = YearMonth.of(y, m);
		double rev = revenueOf(sel);
		double exp = expenseRepo.sumBetween(sel.atDay(1), sel.atEndOfMonth());
		double net = rev - exp;

		String selName = sel.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ENGLISH);
		lblRevenue.setText(selName + " Revenue: " + INT.format(Math.round(rev)));
		lblExpenses.setText("Expenses: " + INT.format(Math.round(exp)));
		lblNet.setText("NET: " + INT.format(Math.round(net)));
		lblNet.setStyle("-fx-text-fill: " + (net < 0 ? "#c0392b" : "#1e8e3e") + ";");

		Map<String, Double> byCat = expenseRepo.sumByCategoryBetween(sel.atDay(1), sel.atEndOfMonth());
		catTable.getItems().setAll(new ArrayList<>(byCat.entrySet()));

		Map<String, Double> byAct = expenseRepo.sumByActivityBetween(sel.atDay(1), sel.atEndOfMonth());
		actTable.getItems().setAll(new ArrayList<>(byAct.entrySet()));
	}

	private double revenueOf(YearMonth ym)
	{
		try
		{
			List<DailySummary> days = dailyRepo.summariesBetween(ym.atDay(1), ym.atEndOfMonth());
			double sum = 0;
			for (DailySummary d : days)
			{
				sum += d.getTotalTtc() == null ? 0 : d.getTotalTtc();
			}
			return sum;
		}
		catch (SQLException e)
		{
			e.printStackTrace();
			return 0;
		}
	}

	/* ===================== Row ===================== */

	static final class MonthRow
	{
		final String label;
		final double revenue;
		final double expenses;

		MonthRow(String label, double revenue, double expenses)
		{
			this.label = label;
			this.revenue = revenue;
			this.expenses = expenses;
		}

		double net()
		{
			return revenue - expenses;
		}
	}
}
