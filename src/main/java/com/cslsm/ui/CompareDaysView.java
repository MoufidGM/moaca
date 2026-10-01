package com.cslsm.ui;

import com.cslsm.model.DailySummary;
import com.cslsm.repo.DailyRepo;
import com.cslsm.util.AppConfig;
import javafx.application.HostServices;
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

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Day A vs Day B compare with table + side-by-side bar chart.
 */
public class CompareDaysView extends BorderPane
{

	private final DailyRepo repo = new DailyRepo(AppConfig.getDbPath());
	private final HostServices host;

	private final DatePicker dpA = new DatePicker(LocalDate.now().minusDays(1));
	private final DatePicker dpB = new DatePicker(LocalDate.now());
	private final Button btnOpenA = new Button();
	private final Button btnOpenB = new Button();
	private final Button btnSwap = new Button("Swap");
	private final Button btnExport = new Button("Export CSV");
	private final Button btnRefresh = new Button("Refresh");

	private final TableView<Row> table = new TableView<>();
	private final DecimalFormat INT = new DecimalFormat("#,##0");

	private final DateTimeFormatter DAY_FMT_SHORT = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy");
	// dynamic column headers
	private final TableColumn<Row, String> cCat = new TableColumn<>("Category");
	private final TableColumn<Row, Number> cA = new TableColumn<>();
	private final TableColumn<Row, Number> cB = new TableColumn<>();
	private final TableColumn<Row, Number> cD = new TableColumn<>("Δ (B - A)");
	// Chart
	private final CategoryAxis xAxis = new CategoryAxis();
	private final NumberAxis yAxis = new NumberAxis();
	private final BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
	// Track hidden series by legend click (don’t remove from chart)
	private final Set<String> hiddenSeries = new HashSet<>();
	private DailySummary sumA, sumB;

	public CompareDaysView(HostServices hostServices)
	{
		this.host = hostServices;

		// Header
		int h = 30;
		for (var c : new Control[]{dpA, dpB, btnOpenA, btnOpenB, btnSwap, btnExport, btnRefresh})
		{
			c.setPrefHeight(h);
		}

		HBox ctrl = new HBox(10, new Label("Day 1:"), dpA, btnOpenA, new Label("Day 2:"), dpB, btnOpenB, btnSwap, btnRefresh, btnExport);
		ctrl.setAlignment(Pos.CENTER_LEFT);
		ctrl.setPadding(new Insets(8, 10, 8, 10));
		setTop(ctrl);

		buildColumns();

		// Chart setup (like Compare Months)
		yAxis.setTickLabelFormatter(new NumberAxis.DefaultFormatter(yAxis)
		{
			@Override
			public String toString(Number n)
			{
				return INT.format(n.longValue());
			}
		});
		chart.setAnimated(false);
		chart.setLegendVisible(true);
		chart.setCategoryGap(12);
		chart.setBarGap(3);
		chart.setStyle("-fx-font-size: 12px;");

		// Center: table (left) + chart (right)
		SplitPane split = new SplitPane(new StackPane(table), new StackPane(chart));
		split.setDividerPositions(0.42); // start with ~42% table
		setCenter(split);
		VBox.setVgrow(split, Priority.ALWAYS);

		btnRefresh.setOnAction(e -> reload());
		btnSwap.setOnAction(e ->
		{
			LocalDate a = dpA.getValue(), b = dpB.getValue();
			dpA.setValue(b);
			dpB.setValue(a);
		});
		btnOpenA.setOnAction(e -> open(sumA));
		btnOpenB.setOnAction(e -> open(sumB));
		dpA.valueProperty().addListener((o, a, b) -> reload());
		dpB.valueProperty().addListener((o, a, b) -> reload());
		btnExport.setOnAction(e -> exportCsv());

		// first paint
		reload();
	}

	private void buildColumns()
	{
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		table.setFixedCellSize(26);
		table.setStyle("-fx-font-size: 14px; -fx-font-family: 'Segoe UI','Helvetica Neue',Arial;");

		cCat.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().category));

		cA.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().a));
		cA.setCellFactory(col -> new TableCell<>()
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
			}
		});

		cB.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().b));
		cB.setCellFactory(col -> new TableCell<>()
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
			}
		});

		cD.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().delta));
		cD.setCellFactory(col -> new TableCell<>()
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
				double d = v.doubleValue();
				setText(INT.format(Math.round(d)));
				String css = d > 0 ? "#1b8d36" : (d < 0 ? "#b31b1b" : "-fx-text-base-color");
				setStyle("-fx-font-weight: 700; -fx-text-fill: " + (d == 0 ? "-fx-text-base-color" : css) + ";");
			}
		});

		table.getColumns().setAll(cCat, cA, cB, cD);
		updateDynamicLabels(); // initial column titles + button text
	}

	/* ===================== Data flow ===================== */

	private void reload()
	{
		try
		{
			sumA = repo.summaryFor(dpA.getValue()).orElse(null);
		}
		catch (Exception ex)
		{
			sumA = null;
		}
		try
		{
			sumB = repo.summaryFor(dpB.getValue()).orElse(null);
		}
		catch (Exception ex)
		{
			sumB = null;
		}

		List<Row> rows = new ArrayList<>();
		rows.add(row("Terrain", v(sumA, "terrain"), v(sumB, "terrain")));
		rows.add(row("Academy", v(sumA, "academy"), v(sumB, "academy")));
		rows.add(row("Gym", v(sumA, "gym"), v(sumB, "gym")));
		rows.add(row("Taekwondo", v(sumA, "tkd"), v(sumB, "tkd")));
		rows.add(row("Padel", v(sumA, "padel"), v(sumB, "padel")));
		rows.add(row("Park", v(sumA, "park"), v(sumB, "park")));
		rows.add(row("Mini Golf", v(sumA, "mini"), v(sumB, "mini")));
		rows.add(row("Ping Pong", v(sumA, "ping"), v(sumB, "ping")));
		rows.add(row("Shoes", v(sumA, "shoes"), v(sumB, "shoes")));
		rows.add(row("Drinks", v(sumA, "drinks"), v(sumB, "drinks")));
		rows.add(row("Cash", v(sumA, "cash"), v(sumB, "cash")));
		rows.add(row("Card", v(sumA, "card"), v(sumB, "card")));
		rows.add(row("Daily Total", v(sumA, "total"), v(sumB, "total")));

		table.getItems().setAll(rows);
		updateDynamicLabels();
		rebuildChart(rows);   // << add chart
	}

	private void updateDynamicLabels()
	{
		LocalDate a = dpA.getValue();
		LocalDate b = dpB.getValue();
		String aLabel = a == null ? "Day 1" : DAY_FMT_SHORT.format(a);
		String bLabel = b == null ? "Day 2" : DAY_FMT_SHORT.format(b);

		cA.setText(aLabel);
		cB.setText(bLabel);

		btnOpenA.setText("Open " + aLabel);
		btnOpenB.setText("Open " + bLabel);

		chart.setTitle("Month vs Month — " + aLabel + " vs " + bLabel); // title style matches compare months
	}

	private double v(DailySummary s, String k)
	{
		if (s == null) return 0;
		return switch (k)
		{
			case "terrain" -> nz(s.getTotalTerrain());
			case "academy" -> nz(s.getTotalAcademyFoot());
			case "gym" -> nz(s.getTotalGym());
			case "tkd" -> nz(s.getTotalTaekwondo());
			case "padel" -> nz(s.getTotalPadel());
			case "park" -> nz(s.getTotalPark());
			case "mini" -> nz(s.getTotalMiniGolf());
			case "ping" -> nz(s.getTotalPingPong());
			case "shoes" -> nz(s.getTotalShoes());
			case "drinks" -> nz(s.getDrinksAmountTotal());
			case "total" -> nz(s.getTotalTtc());
			case "cash" -> nz(s.getTotalCash());
			case "card" -> nz(s.getTotalCard());
			default -> 0;
		};
	}

	private Row row(String name, double a, double b)
	{
		Row r = new Row();
		r.category = name;
		r.a = a;
		r.b = b;
		r.delta = b - a;
		return r;
	}

	private void open(DailySummary s)
	{
		if (s == null || s.getFilePath() == null || s.getFilePath().isBlank()) return;
		try
		{
			Path p = Path.of(s.getFilePath());
			if (Files.exists(p)) host.showDocument(p.toUri().toString());
		}
		catch (Exception ignored)
		{
		}
	}

	private double nz(Double d)
	{
		return d == null ? 0d : d;
	}

	/* ===================== Chart ===================== */

	private void rebuildChart(List<Row> rows)
	{
		chart.getData().clear();
		hiddenSeries.clear();

		// Series names use dynamic column labels (dates)
		String nameA = cA.getText();
		String nameB = cB.getText();

		XYChart.Series<String, Number> sA = new XYChart.Series<>();
		sA.setName(nameA);
		for (Row r : rows)
		{
			sA.getData().add(new XYChart.Data<>(r.category, Math.round(r.a)));
		}

		XYChart.Series<String, Number> sB = new XYChart.Series<>();
		sB.setName(nameB);
		for (Row r : rows)
		{
			sB.getData().add(new XYChart.Data<>(r.category, Math.round(r.b)));
		}

		chart.getData().addAll(sA, sB);

		Platform.runLater(() ->
		{
			installTooltips();
			enableLegendToggle();
			recomputeYAxis();
		});
	}

	/**
	 * Tooltips like “Oct 21 — Gym: 11,800”
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
	 * Compute Y-axis range from visible series only.
	 */
	private void recomputeYAxis()
	{
		double max = 0.0;
		for (XYChart.Series<String, Number> s : chart.getData())
		{
			if (hiddenSeries.contains(s.getName())) continue;
			for (XYChart.Data<String, Number> d : s.getData())
			{
				if (d.getYValue() != null) max = Math.max(max, d.getYValue().doubleValue());
			}
		}
		if (max <= 0)
		{
			yAxis.setAutoRanging(true);
			return;
		}

		double padded = max * 1.10;
		double step = chooseTickUnit(padded);
		double upper = Math.ceil(padded / step) * step;

		yAxis.setAutoRanging(false);
		yAxis.setLowerBound(0);
		yAxis.setUpperBound(upper);
		yAxis.setTickUnit(step);
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

	/* ===================== CSV ===================== */

	private void exportCsv()
	{
		try
		{
			LocalDate a = dpA.getValue(), b = dpB.getValue();
			Path out = Path.of("compare-" + a + "_vs_" + b + ".csv");
			try (BufferedWriter bw = Files.newBufferedWriter(out))
			{
				bw.write("Category," + DAY_FMT_SHORT.format(a) + "," + DAY_FMT_SHORT.format(b) + ",Delta (B-A)");
				bw.newLine();
				for (Row r : table.getItems())
				{
					bw.write(r.category + "," + Math.round(r.a) + "," + Math.round(r.b) + "," + Math.round(r.delta));
					bw.newLine();
				}
			}
			new Alert(Alert.AlertType.INFORMATION, "Exported:\n" + out.toAbsolutePath(), ButtonType.OK).showAndWait();
		}
		catch (Exception ex)
		{
			new Alert(Alert.AlertType.ERROR, "Export failed: " + ex.getMessage(), ButtonType.OK).showAndWait();
		}
	}

	/* ===================== Row model ===================== */
	static class Row
	{
		String category;
		double a, b, delta;
	}
}
