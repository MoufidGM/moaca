package com.cslsm.ui;

import com.cslsm.model.DailySummary;
import com.cslsm.repo.DailyRepo;
import com.cslsm.repo.ExpenseRepo;
import com.cslsm.util.AppConfig;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.awt.*;
import java.io.File;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

public class MonthlyView extends BorderPane
{

	private final DailyRepo repo = new DailyRepo(AppConfig.getDbPath());
	private final ExpenseRepo expenseRepo = new ExpenseRepo(AppConfig.getDbPath());

	// ==== top controls ====
	private final Label title = new Label("MONTH SUMMARY");
	private final ComboBox<Integer> cbYear = new ComboBox<>();
	private final ComboBox<Integer> cbMonth = new ComboBox<>();
	private final Button btnPrev = new Button("◀ Previous");
	private final Button btnNext = new Button("Next ▶");
	private final Button btnRefresh = new Button("Refresh");
	private final Label lblRange = new Label("");
	private final Label lblGrandTotal = new Label("0");
	private final Label lblCash   = new Label("Cash: 0");
	private final Label lblCard   = new Label("Card: 0");
	private final Label lblCheque = new Label("Cheque: 0");
	private final Label lblExpenses = new Label("Expenses: 0");
	private final Label lblNet = new Label("Net: 0");

	// ==== center content ====
	private final TableView<Row> table = new TableView<>();
	private final LineChart<String, Number> chart;

	// Tabs (Summary / Chart)
	private final TabPane tabs = new TabPane();
	private final Tab tabTable = new Tab("Summary Table");
	private final Tab tabChart = new Tab("Monthly Chart");

	// Track which series are hidden (by name). We DO NOT remove them from chart data.
	private final Set<String> hiddenSeries = new HashSet<>();

	private final DecimalFormat INT = new DecimalFormat("#,##0");

	public MonthlyView()
	{
		/* ===================== Title row (centered) ===================== */
		title.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 22));
		lblGrandTotal.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 22));
		lblCash.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
		lblCard.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
		lblCheque.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
		lblExpenses.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
		lblExpenses.setStyle("-fx-text-fill: #c0392b;");
		lblNet.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 22));

		Region spacerL = new Region();
		Region spacerR = new Region();
		HBox.setHgrow(spacerL, Priority.ALWAYS);
		HBox.setHgrow(spacerR, Priority.ALWAYS);

		HBox headerTotals = new HBox(20, lblCash, lblCard, lblCheque, lblGrandTotal, lblExpenses, lblNet);
		headerTotals.setAlignment(Pos.CENTER_RIGHT);

		HBox titleRow = new HBox(10, spacerL, title, spacerR, headerTotals);
		titleRow.setAlignment(Pos.CENTER);
		titleRow.setPadding(new Insets(8, 10, 0, 10));

		/* ===================== Controls row (aligned) ===================== */
		int thisYear = LocalDate.now().getYear();
		for (int y = thisYear - 5; y <= thisYear + 1; y++)
		{
			cbYear.getItems().add(y);
		}
		cbYear.setValue(thisYear);
		for (int m = 1; m <= 12; m++)
		{
			cbMonth.getItems().add(m);
		}
		cbMonth.setValue(LocalDate.now().getMonthValue());

		int h = 30;
		Arrays.asList(cbYear, cbMonth, btnPrev, btnNext, btnRefresh).forEach(c -> c.setPrefHeight(h));

		HBox ctrlRow = new HBox(12, new Label("Year:"), cbYear, new Label("Month:"), cbMonth, new Label("Range:"), lblRange, btnRefresh, btnPrev, btnNext);
		ctrlRow.setAlignment(Pos.CENTER_LEFT);
		ctrlRow.setPadding(new Insets(4, 10, 8, 10));

		VBox top = new VBox(titleRow, ctrlRow);
		setTop(top);

		/* ===================== Table ===================== */
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		table.setFixedCellSize(26);
		table.setStyle("-fx-font-size: 13.5px; -fx-font-family: 'Segoe UI','Helvetica Neue',Arial;");
		buildColumns();

		/* ===================== Chart ===================== */
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
		chart = new LineChart<>(x, y);
		chart.setAnimated(false);
		chart.setLegendVisible(true);
		chart.setCreateSymbols(true); // symbols so tooltips can attach
		chart.setStyle("-fx-font-size: 12px;");

		/* ===================== Tabs ===================== */
		// with:
		VBox tableWrap = new VBox(table);
		VBox.setVgrow(table, Priority.ALWAYS);
		tabTable.setContent(tableWrap);

		tabChart.setContent(new StackPane(chart));
		tabTable.setClosable(false);
		tabChart.setClosable(false);
		tabs.getTabs().setAll(tabTable, tabChart);
		setCenter(tabs);
		VBox.setVgrow(tabs, Priority.ALWAYS);

		/* ===================== Events ===================== */
		cbYear.valueProperty().addListener((o, a, b) -> reload());
		cbMonth.valueProperty().addListener((o, a, b) -> reload());
		btnRefresh.setOnAction(e -> reload());

		btnPrev.setOnAction(e ->
		{
			YearMonth ym = YearMonth.of(valueOrNowYear(), valueOrNowMonth()).minusMonths(1);
			cbYear.setValue(ym.getYear());
			cbMonth.setValue(ym.getMonthValue());
		});
		btnNext.setOnAction(e ->
		{
			YearMonth ym = YearMonth.of(valueOrNowYear(), valueOrNowMonth()).plusMonths(1);
			cbYear.setValue(ym.getYear());
			cbMonth.setValue(ym.getMonthValue());
		});

		reload();
	}

	/* ===================== Build columns ===================== */

	private void buildColumns()
	{
		table.getColumns().setAll(linkCol("Date"), // opens original file
				numCol("Terrain", r -> r.terrain), numCol("Academy", r -> r.academy), numCol("Gym", r -> r.gym), numCol("Taekwondo", r -> r.taekwondo), numCol("Padel", r -> r.padel), numCol("Park", r -> r.park), numCol("Mini Golf", r -> r.miniGolf), numCol("Ping Pong", r -> r.pingPong), numCol("Shoes", r -> r.shoes), numCol("Drinks", r -> r.drinks), numCol("Daily Total", r -> r.dailyTotal));
	}

	private TableColumn<Row, String> linkCol(String title)
	{
		TableColumn<Row, String> c = new TableColumn<>(title);
		c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().label));
		c.setCellFactory(col -> new TableCell<>()
		{
			private final Hyperlink link = new Hyperlink();
			private final Label lab = new Label();

			@Override
			protected void updateItem(String v, boolean empty)
			{
				super.updateItem(v, empty);
				if (empty)
				{
					setGraphic(null);
					setText(null);
					return;
				}
				Row r = getTableView().getItems().get(getIndex());
				boolean selected = getTableRow() != null && getTableRow().isSelected();
				boolean isTotal = "TOTAL".equals(r.label);
				if (isTotal)
				{ // total row not a link
					lab.setText(r.label);
					lab.setStyle((selected ? "-fx-text-fill: white; " : "") + "-fx-font-weight:700; -fx-font-size: 14px;");
					setGraphic(lab);
					return;
				}
				link.setText(v);
				link.setOnAction(e -> openFile(r.filePath));
				link.setStyle(selected ? "-fx-text-fill: white;" : "");
				setGraphic(link);
			}
		});
		return c;
	}

	/**
	 *
	 * @param T
	 */
	private void refreshPaymentHeader(Row T)
	{
		if (T == null) return;
		lblCash.setText("Cash: " + INT.format(Math.round(T.cash)));
		lblCard.setText("Card: " + INT.format(Math.round(T.card)));
		lblCheque.setText("Cheque: " + INT.format(Math.round(T.cheque)));
	}

	private TableColumn<Row, Number> numCol(String title, ToDoubleFunction<Row> f)
	{
		TableColumn<Row, Number> c = new TableColumn<>(title);
		c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.applyAsDouble(cd.getValue())));
		c.setCellFactory(col -> new TableCell<>()
		{
			@Override
			protected void updateItem(Number v, boolean empty)
			{
				super.updateItem(v, empty);
				if (empty)
				{
					setText("");
					setStyle("");
					return;
				}
				setText(INT.format(Math.round(v.doubleValue())));
				Row r = getTableView().getItems().get(getIndex());
				boolean selected = getTableRow() != null && getTableRow().isSelected();
				boolean isTotal = "TOTAL".equals(r.label);
				String base = selected ? "-fx-text-fill: white;" : "";
				if (isTotal) setStyle(base + "-fx-font-weight:700; -fx-font-size: 14px;");
				else setStyle(base);
			}
		});
		return c;
	}

	/* ===================== Data + chart ===================== */

	private void reload()
	{
		int y = valueOrNowYear();
		int m = valueOrNowMonth();
		YearMonth ym = YearMonth.of(y, m);

		// Title like "OCTOBER 2025 SUMMARY"
		Locale loc = Locale.ENGLISH;
		title.setText(ym.getMonth().getDisplayName(TextStyle.FULL, loc).toUpperCase(loc) + " " + y + " SUMMARY");

		LocalDate start = ym.atDay(1);
		LocalDate end = ym.atEndOfMonth();
		lblRange.setText(start + " → " + end);

		List<Row> rows = new ArrayList<>();
		LocalDate d = start;
		while (!d.isAfter(end))
		{
			Row r = toRow(d);
			rows.add(r);
			d = d.plusDays(1);
		}

		// Totals
		Row T = new Row();
		T.label = "TOTAL";
		for (Row r : rows)
		{
			T.terrain += r.terrain;
			T.academy += r.academy;
			T.gym += r.gym;
			T.taekwondo += r.taekwondo;
			T.padel += r.padel;
			T.park += r.park;
			T.miniGolf += r.miniGolf;
			T.pingPong += r.pingPong;
			T.shoes += r.shoes;
			T.drinks += r.drinks;
			T.cash += r.cash;
			T.card += r.card;
			T.cheque += r.cheque;
			T.dailyTotal += r.dailyTotal;
		}
		rows.add(T);

		table.getItems().setAll(rows);


		lblGrandTotal.setText("TOTAL TTC: " + INT.format(Math.round(T.dailyTotal)));
		lblCash.setText("Cash: " + INT.format(Math.round(T.cash)));
		lblCard.setText("Card: " + INT.format(Math.round(T.card)));
		lblCheque.setText("Cheque: " + INT.format(Math.round(T.cheque)));

		double expenses = expenseRepo.sumBetween(start, end);
		double net = T.dailyTotal - expenses;
		lblExpenses.setText("Expenses: " + INT.format(Math.round(expenses)));
		lblNet.setText("Net: " + INT.format(Math.round(net)));
		lblNet.setStyle("-fx-text-fill: " + (net < 0 ? "#c0392b" : "#1e8e3e") + ";");

		refreshPaymentHeader(T);

		rebuildChart(rows.stream().filter(r -> !"TOTAL".equals(r.label)).collect(Collectors.toList()));
	}

	private void rebuildChart(List<Row> rows)
	{
		chart.getData().clear();

		// One series per category (omit TOTAL)
		chart.getData().add(series("Terrain", rows, r -> r.terrain));
		chart.getData().add(series("Academy", rows, r -> r.academy));
		chart.getData().add(series("Gym", rows, r -> r.gym));
		chart.getData().add(series("Taekwondo", rows, r -> r.taekwondo));
		chart.getData().add(series("Padel", rows, r -> r.padel));
		chart.getData().add(series("Park", rows, r -> r.park));
		chart.getData().add(series("Mini Golf", rows, r -> r.miniGolf));
		chart.getData().add(series("Ping Pong", rows, r -> r.pingPong));
		chart.getData().add(series("Shoes", rows, r -> r.shoes));
		chart.getData().add(series("Drinks", rows, r -> r.drinks));
		chart.getData().add(series("Daily Total", rows, r -> r.dailyTotal));

		// Apply hidden state without removing data (legend remains)
		Platform.runLater(() ->
		{
			for (XYChart.Series<String, Number> s : chart.getData())
			{
				boolean hide = hiddenSeries.contains(s.getName());
				if (s.getNode() != null) s.getNode().setVisible(!hide);
				for (XYChart.Data<String, Number> d : s.getData())
				{
					if (d.getNode() != null) d.getNode().setVisible(!hide);
				}
			}
			installPointTooltips();
			enableLegendToggle();
			recomputeYAxis(); // based on visible series
		});
	}

	/**
	 * Adds tooltips like "Terrain — Wed 1: 6,200" to each symbol.
	 */
	private void installPointTooltips()
	{
		for (XYChart.Series<String, Number> s : chart.getData())
		{
			for (XYChart.Data<String, Number> d : s.getData())
			{
				if (d.getNode() == null) continue;
				String txt = s.getName() + " — " + d.getXValue() + ": " + INT.format(d.getYValue() == null ? 0L : d.getYValue().longValue());
				Tooltip.install(d.getNode(), new Tooltip(txt));
				d.getNode().setStyle("-fx-background-radius: 3px; -fx-padding: 3px;");
			}
		}
	}

	/**
	 * Legend toggles visibility; Y-axis re-ranges to visible data only.
	 */
	private void enableLegendToggle()
	{
		Map<String, XYChart.Series<String, Number>> byName = new LinkedHashMap<>();
		for (XYChart.Series<String, Number> s : chart.getData())
		{
			byName.put(s.getName(), s);
		}

		for (Node item : chart.lookupAll(".chart-legend-item"))
		{
			Label label = (Label) item.lookup(".label");
			if (label == null) continue;
			final String seriesName = label.getText();
			final XYChart.Series<String, Number> series = byName.get(seriesName);
			if (series == null) continue;

			item.setCursor(Cursor.HAND);
			// reflect current hidden state
			item.setOpacity(hiddenSeries.contains(seriesName) ? 0.35 : 1.0);

			item.setOnMouseClicked(ev ->
			{
				boolean nowHidden = !hiddenSeries.contains(seriesName);
				if (nowHidden) hiddenSeries.add(seriesName);
				else hiddenSeries.remove(seriesName);

				// toggle visibility without removing
				boolean show = !nowHidden;
				if (series.getNode() != null) series.getNode().setVisible(show);
				for (XYChart.Data<String, Number> d : series.getData())
				{
					if (d.getNode() != null) d.getNode().setVisible(show);
				}
				item.setOpacity(show ? 1.0 : 0.35);

				recomputeYAxis();
			});
		}
	}

	/**
	 * Compute Y-axis range from visible series only (nice rounded upper bound).
	 */
	private void recomputeYAxis()
	{
		double max = 0.0;
		for (XYChart.Series<String, Number> s : chart.getData())
		{
			if (hiddenSeries.contains(s.getName())) continue; // only visible
			for (XYChart.Data<String, Number> d : s.getData())
			{
				if (d.getYValue() != null)
				{
					max = Math.max(max, d.getYValue().doubleValue());
				}
			}
		}
		NumberAxis y = (NumberAxis) chart.getYAxis();
		if (max <= 0)
		{
			y.setAutoRanging(true);
			return;
		}
		double padded = max * 1.10;
		double step = chooseTickUnit(padded);
		double upper = Math.ceil(padded / step) * step;

		y.setAutoRanging(false);
		y.setLowerBound(0);
		y.setUpperBound(upper);
		y.setTickUnit(step);
	}

	/**
	 * Pick a readable tick step based on magnitude.
	 */
	private double chooseTickUnit(double v)
	{
		if (v <= 1000) return 100;
		if (v <= 5000) return 250;
		if (v <= 10000) return 500;
		if (v <= 50000) return 1000;
		if (v <= 100000) return 5000;
		return 10000;
	}

	private XYChart.Series<String, Number> series(String name, List<Row> rows, ToDoubleFunction<Row> f)
	{
		XYChart.Series<String, Number> s = new XYChart.Series<>();
		s.setName(name);
		for (Row r : rows)
		{
			s.getData().add(new XYChart.Data<>(r.labelShort, Math.round(f.applyAsDouble(r))));
		}
		return s;
	}

	private Row toRow(LocalDate d)
	{
		Row r = new Row();
		Locale loc = Locale.ENGLISH;
		r.label = d.getDayOfWeek().getDisplayName(TextStyle.FULL, loc) + " " + ordinal(d.getDayOfMonth());
		r.labelShort = d.getDayOfWeek().getDisplayName(TextStyle.SHORT, loc) + " " + d.getDayOfMonth();

		try
		{
			var opt = repo.summaryFor(d);
			if (opt.isPresent())
			{
				DailySummary s = opt.get();
				r.terrain = nz(s.getTotalTerrain());
				r.academy = nz(s.getTotalAcademyFoot());
				r.gym = nz(s.getTotalGym());
				r.taekwondo = nz(s.getTotalTaekwondo());
				r.padel = nz(s.getTotalPadel());
				r.park = nz(s.getTotalPark());
				r.miniGolf = nz(s.getTotalMiniGolf());
				r.pingPong = nz(s.getTotalPingPong());
				r.shoes = nz(s.getTotalShoes());
				r.drinks = nz(s.getDrinksAmountTotal());
				r.cash = nz(s.getTotalCash());
				r.card = nz(s.getTotalCard());
				r.cheque = nz(s.getTotalCheque());
				r.dailyTotal = nz(s.getTotalTtc());
				r.filePath = s.getFilePath();
			}
		}
		catch (Exception ignored)
		{
		}
		return r;
	}

	/* ===================== helpers ===================== */

	private int valueOrNowYear()
	{
		return cbYear.getValue() != null ? cbYear.getValue() : LocalDate.now().getYear();
	}

	private int valueOrNowMonth()
	{
		return cbMonth.getValue() != null ? cbMonth.getValue() : LocalDate.now().getMonthValue();
	}

	private String ordinal(int n)
	{
		if (n >= 11 && n <= 13) return n + "th";
		return switch (n % 10)
		{
			case 1 -> n + "st";
			case 2 -> n + "nd";
			case 3 -> n + "rd";
			default -> n + "th";
		};
	}

	private void openFile(String path)
	{
		try
		{
			if (path == null || path.isBlank()) return;
			File f = new File(path);
			if (f.exists()) Desktop.getDesktop().open(f);
		}
		catch (Exception ex)
		{
			ex.printStackTrace();
		}
	}


	private double nz(Double d)
	{
		return d == null ? 0d : d;
	}

	// ===== Row model =====
	static class Row
	{
		String label;       // "Wednesday 1st" / "TOTAL"
		String labelShort;  // "Wed 1"
		double terrain, academy, gym, taekwondo, padel, park, miniGolf, pingPong, shoes, drinks, dailyTotal;
		double cash, card, cheque;
		String filePath;    // original Excel file
	}
}
