package com.cslsm.ui;

import com.cslsm.model.DailySummary;
import com.cslsm.repo.DailyRepo;
import com.cslsm.util.AppConfig;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;
import java.util.function.ToDoubleFunction;

public class YearlyView extends BorderPane
{

	private final DailyRepo repo = new DailyRepo(AppConfig.getDbPath());

	// ==== top controls ====
	private final Label title = new Label("YEAR SUMMARY");
	private final ComboBox<Integer> cbYear = new ComboBox<>();
	private final Button btnPrev = new Button("◀ Previous year");
	private final Button btnNext = new Button("Next year ▶");
	private final Label lblGrandTotal = new Label("0");

	// ==== center content ====
	private final TableView<Row> table = new TableView<>();
	private final BarChart<String, Number> chart;

	// Track hidden/visible series by name (we do not remove series to keep legend/tooltips intact)
	private final Set<String> hiddenSeries = new HashSet<>();

	private final DecimalFormat INT = new DecimalFormat("#,##0");

	// Activity registry (column label -> extractor)
	private final LinkedHashMap<String, ToDoubleFunction<Row>> ACTIVITIES = new LinkedHashMap<>();

	public YearlyView()
	{
		/* ===================== Title row (centered) ===================== */
		title.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 22));
		lblGrandTotal.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 22));

		Region spacerL = new Region();
		Region spacerR = new Region();
		HBox.setHgrow(spacerL, Priority.ALWAYS);
		HBox.setHgrow(spacerR, Priority.ALWAYS);

		HBox titleRow = new HBox(10, spacerL, title, spacerR, new Label("TOTAL TTC:"), lblGrandTotal);
		titleRow.setAlignment(Pos.CENTER);
		titleRow.setPadding(new Insets(8, 10, 0, 10));

		/* ===================== Controls row (aligned) ===================== */
		int thisYear = LocalDate.now().getYear();
		for (int y = thisYear - 5; y <= thisYear + 1; y++)
		{
			cbYear.getItems().add(y);
		}
		cbYear.setValue(thisYear);

		int h = 30;
		Arrays.asList(cbYear, btnPrev, btnNext).forEach(c -> c.setPrefHeight(h));

		HBox ctrlRow = new HBox(12, btnPrev, new Label("Year:"), cbYear, btnNext);
		ctrlRow.setAlignment(Pos.CENTER_LEFT);
		ctrlRow.setPadding(new Insets(6, 10, 8, 10));

		VBox top = new VBox(titleRow, ctrlRow);
		setTop(top);

		/* ===================== Activities registry ===================== */
		ACTIVITIES.put("Terrain", r -> r.terrain);
		ACTIVITIES.put("Academy", r -> r.academy);
		ACTIVITIES.put("Gym", r -> r.gym);
		ACTIVITIES.put("Taekwondo", r -> r.taekwondo);
		ACTIVITIES.put("Padel", r -> r.padel);
		ACTIVITIES.put("Park", r -> r.park);
		ACTIVITIES.put("Mini Golf", r -> r.miniGolf);
		ACTIVITIES.put("Ping Pong", r -> r.pingPong);
		ACTIVITIES.put("Shoes", r -> r.shoes);
		ACTIVITIES.put("Drinks", r -> r.drinks);
		ACTIVITIES.put("Monthly Total", r -> r.monthlyTotal);

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
		chart = new BarChart<>(x, y);
		chart.setAnimated(false);
		chart.setLegendVisible(true);
		chart.setCategoryGap(12);
		chart.setBarGap(3);
		chart.setStyle("-fx-font-size: 12px;");

		// Layout center: table above, chart below
		VBox center = new VBox(8, table, chart);
		center.setPadding(new Insets(6, 10, 10, 10));
		VBox.setVgrow(chart, Priority.ALWAYS);
		setCenter(center);

		/* ===================== Events ===================== */
		cbYear.valueProperty().addListener((o, a, b) -> reload());
		btnPrev.setOnAction(e -> cbYear.setValue(cbYear.getValue() - 1));
		btnNext.setOnAction(e -> cbYear.setValue(cbYear.getValue() + 1));

		reload();
	}

	/* ===================== Build UI pieces ===================== */

	private void buildColumns()
	{
		table.getColumns().clear();

		// Month column
		TableColumn<Row, String> cMonth = new TableColumn<>("Month");
		cMonth.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().label));
		table.getColumns().add(cMonth);

		// Numeric columns
		table.getColumns().add(numCol("Terrain", r -> r.terrain));
		table.getColumns().add(numCol("Academy", r -> r.academy));
		table.getColumns().add(numCol("Gym", r -> r.gym));
		table.getColumns().add(numCol("Taekwondo", r -> r.taekwondo));
		table.getColumns().add(numCol("Padel", r -> r.padel));
		table.getColumns().add(numCol("Park", r -> r.park));
		table.getColumns().add(numCol("Mini Golf", r -> r.miniGolf));
		table.getColumns().add(numCol("Ping Pong", r -> r.pingPong));
		table.getColumns().add(numCol("Shoes", r -> r.shoes));
		table.getColumns().add(numCol("Drinks", r -> r.drinks));
		table.getColumns().add(numCol("Cash",   r -> r.cash));   // <-- add
		table.getColumns().add(numCol("Card",   r -> r.card));   // <-- add
		table.getColumns().add(numCol("Monthly Total", r -> r.monthlyTotal));
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
		int y = cbYear.getValue() != null ? cbYear.getValue() : LocalDate.now().getYear();

		// Title like "YEAR 2025 SUMMARY"
		title.setText("YEAR " + y + " SUMMARY");

		List<Row> rows = new ArrayList<>();
		double grand = 0;
		for (int m = 1; m <= 12; m++)
		{
			YearMonth ym = YearMonth.of(y, m);
			Row r = toRow(ym);
			rows.add(r);
			grand += r.monthlyTotal;
		}

		// TOTAL row
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
T.monthlyTotal += r.monthlyTotal;
		}
		rows.add(T);

		table.getItems().setAll(rows);
		sizeTableToRows();

		lblGrandTotal.setText(INT.format(Math.round(grand)));

		rebuildChart(rows.subList(0, rows.size() - 1)); // exclude TOTAL from chart
	}

	private void rebuildChart(List<Row> rows)
	{
		chart.getData().clear();
		hiddenSeries.clear(); // show all by default on each reload

		for (Map.Entry<String, ToDoubleFunction<Row>> e : ACTIVITIES.entrySet())
		{
			String name = e.getKey();
			XYChart.Series<String, Number> s = new XYChart.Series<>();
			s.setName(name);
			ToDoubleFunction<Row> fn = e.getValue();
			for (Row r : rows)
			{
				s.getData().add(new XYChart.Data<>(r.monthShort, Math.round(fn.applyAsDouble(r))));
			}
			chart.getData().add(s);
		}

		Platform.runLater(() ->
		{
			installTooltips();
			enableLegendToggle();   // legend-only show/hide
			recomputeYAxis();       // initial scale
		});
	}

	/**
	 * Tooltips like "Terrain — SEP: 12,450"
	 */
	private void installTooltips()
	{
		for (XYChart.Series<String, Number> s : chart.getData())
		{
			for (XYChart.Data<String, Number> d : s.getData())
			{
				if (d.getNode() == null) continue;
				String txt = s.getName() + " — " + d.getXValue() + ": " + INT.format(d.getYValue() == null ? 0L : d.getYValue().longValue());
				Tooltip.install(d.getNode(), new Tooltip(txt));
			}
		}
	}

	/**
	 * Legend toggles visibility (keeps legend items) and rescales Y axis.
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
			item.setOpacity(hiddenSeries.contains(seriesName) ? 0.35 : 1.0);

			item.setOnMouseClicked(ev ->
			{
				boolean nowHidden = !hiddenSeries.contains(seriesName);
				if (nowHidden) hiddenSeries.add(seriesName);
				else hiddenSeries.remove(seriesName);

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
				if (d.getYValue() != null) max = Math.max(max, d.getYValue().doubleValue());
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

	private double chooseTickUnit(double v)
	{
		if (v <= 1000) return 100;
		if (v <= 5000) return 250;
		if (v <= 10000) return 500;
		if (v <= 50000) return 1000;
		if (v <= 100000) return 5000;
		return 10000;
	}

	/* ===================== Data aggregation ===================== */

	private Row toRow(YearMonth ym)
	{
		Row r = new Row();
		var loc = Locale.ENGLISH;
		r.label = ym.getMonth().getDisplayName(TextStyle.FULL, loc).toUpperCase(loc);
		r.monthShort = ym.getMonth().getDisplayName(TextStyle.SHORT, loc).toUpperCase(loc);

		LocalDate d = ym.atDay(1);
		LocalDate end = ym.atEndOfMonth();

		while (!d.isAfter(end))
		{
			try
			{
				var opt = repo.summaryFor(d);
				if (opt.isPresent())
				{
					DailySummary s = opt.get();
					r.terrain += nz(s.getTotalTerrain());
					r.academy += nz(s.getTotalAcademyFoot());
					r.gym += nz(s.getTotalGym());
					r.taekwondo += nz(s.getTotalTaekwondo());
					r.padel += nz(s.getTotalPadel());
					r.park += nz(s.getTotalPark());
					r.miniGolf += nz(s.getTotalMiniGolf());
					r.pingPong += nz(s.getTotalPingPong());
					r.shoes += nz(s.getTotalShoes());
					r.drinks += nz(s.getDrinksAmountTotal());
					r.cash += nz(s.getTotalCash());   // <-- add
					r.card += nz(s.getTotalCard());   // <-- add
					r.monthlyTotal += nz(s.getTotalTtc());
				}
			}
			catch (Exception ignored)
			{
			}
			d = d.plusDays(1);
		}
		return r;
	}

	private void sizeTableToRows()
	{
		double h = table.getFixedCellSize() * (table.getItems().size() + 1.2);
		table.setMinHeight(h);
		table.setPrefHeight(h);
		table.setMaxHeight(Double.MAX_VALUE);
	}

	private double nz(Double d)
	{
		return d == null ? 0d : d;
	}

	/* ===================== Row model ===================== */
	static class Row
	{
		String label;       // "JULY", "AUGUST", ... or "TOTAL"
		String monthShort;  // "JUL", "AUG", ...
		double terrain, academy, gym, taekwondo, padel, park, miniGolf, pingPong, shoes, drinks, monthlyTotal;
		double cash, card;  // <-- add this line
	}
}
