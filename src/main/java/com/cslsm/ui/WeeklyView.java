package com.cslsm.ui;

import com.cslsm.model.DailySummary;
import com.cslsm.repo.DailyRepo;
import com.cslsm.util.AppConfig;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

public class WeeklyView extends BorderPane
{

	private final DailyRepo repo = new DailyRepo(AppConfig.getDbPath());

	// top controls
	private final Label title = new Label("WEEK SUMMARY");
	private final Button btnPrev = new Button("◀  Previous week");
	private final Button btnNext = new Button("Next week  ▶");
	private final DatePicker dp = new DatePicker(LocalDate.now());
	private final Label lblRange = new Label();
	private final Label lblGrandTotal = new Label("0");
	private final Label lblCash   = new Label("Cash: 0");
	private final Label lblCard   = new Label("Card: 0");
	private final Label lblCheque = new Label("Cheque: 0");

	// table + chart
	private final TableView<Row> table = new TableView<>();
	private final LineChart<String, Number> chart;

	private final DecimalFormat INT = new DecimalFormat("#,##0");

	// series order & distinct colors (aligned to MonthlyView)
	private final String[] order = {"Terrain", "Academy", "Gym", "Taekwondo", "Padel", "Park", "Mini Golf", "Ping Pong", "Shoes", "Drinks", "Daily Total"};
	private final String[] colors = {"#e15759", // Terrain
			"#f28e2b", // Academy
			"#4e79a7", // Gym
			"#59a14f", // Taekwondo
			"#edc949", // Padel
			"#b07aa1", // Park
			"#76b7b2", // Mini Golf
			"#ff9da7", // Ping Pong
			"#9c755f", // Shoes
			"#bab0ab", // Drinks
			"#2ca02c"  // Daily Total
	};

	public WeeklyView()
	{
		/* -------- Top: Title row -------- */
		title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));

		Region spacerL = new Region();
		Region spacerR = new Region();
		HBox.setHgrow(spacerL, Priority.ALWAYS);
		HBox.setHgrow(spacerR, Priority.ALWAYS);

		lblGrandTotal.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 22));
		lblGrandTotal.setStyle("-fx-text-fill: -fx-text-base-color;");

		lblCash.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
		lblCard.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
		lblCheque.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));


		HBox headerTotals = new HBox(20, lblCash, lblCard, lblCheque, lblGrandTotal /* or lblTotals if that's its name */);
		headerTotals.setAlignment(Pos.CENTER_RIGHT);

		HBox titleRow =new HBox(10, spacerL, title, spacerR, headerTotals);
		titleRow.setAlignment(Pos.CENTER);
		titleRow.setPadding(new Insets(8, 10, 0, 10));



		/* -------- Top: Controls row -------- */
		HBox ctrlRow = new HBox(12, btnPrev, new Label("Week date:"), dp, btnNext, new Label("Range:"), lblRange);
		ctrlRow.setPadding(new Insets(4, 10, 8, 10));
		ctrlRow.setAlignment(Pos.CENTER_LEFT);

		VBox top = new VBox(titleRow, ctrlRow);
		setTop(top);

		/* -------- Center: Table (top) + Chart (fills) -------- */
		// table
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		table.setFixedCellSize(26);
		table.setStyle("-fx-font-size: 13px; -fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial;");
		buildColumns();

		// chart
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
		chart.setCreateSymbols(true);
		chart.setStyle("-fx-font-size: 12px;");
		VBox.setVgrow(chart, Priority.ALWAYS);

		VBox center = new VBox(6, table, chart);
		center.setPadding(new Insets(6, 10, 10, 10));
		setCenter(center);

		// events
		dp.valueProperty().addListener((o, a, b) -> reload());
		btnPrev.setOnAction(e ->
		{
			var d = dp.getValue() != null ? dp.getValue() : java.time.LocalDate.now();
			dp.setValue(d.minusWeeks(1));
		});
		btnNext.setOnAction(e ->
		{
			var d = dp.getValue() != null ? dp.getValue() : java.time.LocalDate.now();
			dp.setValue(d.plusWeeks(1));
		});

		reload(); // initial
	}

	/* ===================== UI building ===================== */

	private static String ordinal(int n)
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

	private static double nz(Double d)
	{
		return d == null ? 0.0 : d;
	}

	private void buildColumns()
	{
		table.getColumns().setAll(linkCol("Date"),            // Thursday 7th
				numCol("Terrain", r -> r.terrain), numCol("Academy", r -> r.academy), numCol("Gym", r -> r.gym), numCol("Taekwondo", r -> r.taekwondo), numCol("Padel", r -> r.padel), numCol("Park", r -> r.park), numCol("Mini Golf", r -> r.miniGolf), numCol("Ping Pong", r -> r.pingPong), numCol("Shoes", r -> r.shoes), numCol("Drinks", r -> r.drinks), numCol("Daily Total", r -> r.dailyTotal));
	}

	private TableColumn<Row, String> textCol(String title, java.util.function.Function<Row, String> f)
	{
		TableColumn<Row, String> c = new TableColumn<>(title);
		c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue())));
		c.setCellFactory(col -> new TableCell<>()
		{
			@Override
			protected void updateItem(String v, boolean empty)
			{
				super.updateItem(v, empty);
				if (empty)
				{
					setText("");
					return;
				}
				setText(v);
				Row r = getTableView().getItems().get(getIndex());
				setStyle("TOTAL".equals(r.label) ? "-fx-font-weight:700;" : "");
			}
		});
		return c;
	}

	/* ===================== Data + chart ===================== */

	private TableColumn<Row, String> linkCol(String title)
	{
		TableColumn<Row, String> c = new TableColumn<>(title);
		c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().label));
		c.setCellFactory(col -> new TableCell<>()
		{
			private final Hyperlink link = new Hyperlink();

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
				if ("TOTAL".equals(r.label))
				{
					setGraphic(new Label(r.label));
					return;
				}
				link.setText(v);
				link.setOnAction(e ->
				{
					try
					{
						if (r.filePath != null && !r.filePath.isBlank())
						{
							File f = new File(r.filePath);
							if (f.exists()) Desktop.getDesktop().open(f);
						}
					}
					catch (Exception ex)
					{
						ex.printStackTrace();
					}
				});
				setGraphic(link);
			}
		});
		return c;
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
					return;
				}
				setText(INT.format(Math.round(v.doubleValue())));
				Row r = getTableView().getItems().get(getIndex());
				setStyle("TOTAL".equals(r.label) ? "-fx-font-weight:700;" : "");
			}
		});
		return c;
	}

	private void reload()
	{
		LocalDate picked = dp.getValue() != null ? dp.getValue() : LocalDate.now();
		LocalDate start = picked.with(DayOfWeek.MONDAY);
		LocalDate end = picked.with(DayOfWeek.SUNDAY);

		// Title: "WEEK OF 6–12 OCT 2025"
		String weekTitle = String.format("WEEK OF %d–%d %s %d", start.getDayOfMonth(), end.getDayOfMonth(), end.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ENGLISH), end.getYear());
		title.setText(weekTitle);

		lblRange.setText(start + " → " + end);

		List<DailySummary> days;
		try
		{
			days = repo.summariesBetween(start, end);
		}
		catch (SQLException e)
		{
			e.printStackTrace();
			days = List.of();
		}

		List<Row> rows = days.stream().sorted(Comparator.comparing(this::toDate)).map(this::toRow).collect(Collectors.toCollection(ArrayList::new));

		Row T = totalRow(rows);
		rows.add(T);

		table.getItems().setAll(rows);
		sizeTableToRows();
		double total  = T.dailyTotal;
		double cash   = T.cash;
		double card   = T.card;
		double cheque = T.cheque;

		lblGrandTotal.setText("Total TTC: " + INT.format(Math.round(total)));
		lblCash.setText("Cash: "   + INT.format(Math.round(cash)));
		lblCard.setText("Card: "   + INT.format(Math.round(card)));
		lblCheque.setText("Cheque: " + INT.format(Math.round(cheque)));

		refreshPaymentHeader(T);

		rebuildChart(rows.stream().filter(r -> !"TOTAL".equals(r.label)).toList());
		Platform.runLater(this::enableLegendToggle);
	}

	/* ===================== Legend interactivity ===================== */

	private void rebuildChart(List<Row> rows)
	{
		chart.getData().clear();

		// x-axis categories = day-of-month short ("7","8",...)
		List<String> xCats = rows.stream().map(r -> r.labelShort).toList();
		((CategoryAxis) chart.getXAxis()).setCategories(FXCollections.observableArrayList(xCats));

		// build series in fixed order
		List<XYChart.Series<String, Number>> seriesList = new ArrayList<>();
		seriesList.add(series("Terrain", rows, r -> r.terrain));
		seriesList.add(series("Academy", rows, r -> r.academy));
		seriesList.add(series("Gym", rows, r -> r.gym));
		seriesList.add(series("Taekwondo", rows, r -> r.taekwondo));
		seriesList.add(series("Padel", rows, r -> r.padel));
		seriesList.add(series("Park", rows, r -> r.park));
		seriesList.add(series("Mini Golf", rows, r -> r.miniGolf));
		seriesList.add(series("Ping Pong", rows, r -> r.pingPong));
		seriesList.add(series("Shoes", rows, r -> r.shoes));
		seriesList.add(series("Drinks", rows, r -> r.drinks));
		seriesList.add(series("Daily Total", rows, r -> r.dailyTotal));

		chart.getData().addAll(seriesList);

		// apply distinct colors
		for (int i = 0; i < seriesList.size(); i++)
		{
			String color = colors[i % colors.length];
			XYChart.Series<String, Number> s = seriesList.get(i);
			Node line = s.getNode();
			if (line != null) line.setStyle("-fx-stroke: " + color + ";");
			for (XYChart.Data<String, Number> d : s.getData())
			{
				Node sym = d.getNode();
				if (sym != null) sym.setStyle("-fx-background-color: " + color + ", white;");
			}
		}
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

	/* ===================== helpers ===================== */

	private void enableLegendToggle()
	{
		Node legend = chart.lookup(".chart-legend");
		if (legend == null) return;

		Map<String, XYChart.Series<String, Number>> byName = chart.getData().stream().collect(Collectors.toMap(XYChart.Series::getName, s -> s, (a, b) -> a, LinkedHashMap::new));

		for (Node item : legend.lookupAll(".chart-legend-item"))
		{
			Label label = (Label) item.lookup(".label");
			if (label == null) continue;
			String name = label.getText();
			XYChart.Series<String, Number> s = byName.get(name);
			if (s == null) continue;

			item.setOnMouseClicked(ev ->
			{
				boolean nowVisible = s.getNode() == null || s.getNode().isVisible();
				setSeriesVisible(s, !nowVisible);
				item.setOpacity(nowVisible ? 0.35 : 1.0); // dim when hidden
			});
		}
	}

	private void setSeriesVisible(XYChart.Series<String, Number> s, boolean visible)
	{
		if (s.getNode() != null) s.getNode().setVisible(visible);
		for (XYChart.Data<String, Number> d : s.getData())
		{
			if (d.getNode() != null) d.getNode().setVisible(visible);
		}
	}


	private void refreshPaymentHeader(Row T) {
		if (T == null) return;
		lblCash.setText("Cash: " + INT.format(Math.round(T.cash)));
		lblCard.setText("Card: " + INT.format(Math.round(T.card)));
		lblCheque.setText("Cheque: " + INT.format(Math.round(T.cheque)));
	}

	private void sizeTableToRows()
	{
		double h = table.getFixedCellSize() * (table.getItems().size() + 1.25); // + header
		table.setPrefHeight(h);
		table.setMinHeight(h);
		table.setMaxHeight(h);
	}

	private LocalDate toDate(DailySummary s)
	{
		try
		{
			var m = DailySummary.class.getMethod("getLogDate");
			Object out = m.invoke(s);
			if (out instanceof LocalDate ld) return ld;
			if (out instanceof String str && !str.isBlank()) return LocalDate.parse(str);
		}
		catch (Exception ignored)
		{
		}
		return null;
	}

	private Row toRow(DailySummary s)
	{
		LocalDate d = toDate(s);
		Row r = new Row();
		// Date e.g., "Thursday 7th"
		r.label = d == null ? "" : d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ordinal(d.getDayOfMonth());
		r.labelShort = d == null ? "" : String.valueOf(d.getDayOfMonth());

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
		r.cheque= nz(s.getTotalCheque());
		r.dailyTotal = nz(s.getTotalTtc());
		r.filePath = s.getFilePath();
		return r;
	}

	private Row totalRow(List<Row> rows)
	{
		Row t = new Row();
		t.label = "TOTAL";
		t.labelShort = "TOTAL";
		for (Row r : rows)
		{
			t.terrain += r.terrain;
			t.academy += r.academy;
			t.gym += r.gym;
			t.taekwondo += r.taekwondo;
			t.padel += r.padel;
			t.park += r.park;
			t.miniGolf += r.miniGolf;
			t.pingPong += r.pingPong;
			t.shoes += r.shoes;
			t.drinks += r.drinks;
			t.cash += r.cash;   // add this
			t.card += r.card;   // add this
			t.cheque += r.cheque;
			t.dailyTotal += r.dailyTotal;
		}
		return t;
	}

	/* table row model */
	private static class Row
	{
		String label;       // "Thursday 7th" / "TOTAL"
		String labelShort;  // "7" / "TOTAL"
		double terrain, academy, gym, taekwondo, padel, park, miniGolf, pingPong, shoes, drinks, dailyTotal;
		double cash, card, cheque;
		String filePath;    // original Excel file
	}
}

