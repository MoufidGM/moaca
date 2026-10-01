package com.cslsm.ui;

import com.cslsm.repo.DailyRepo;
import com.cslsm.util.AppConfig;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;

public class CompareMonthsView extends BorderPane
{

	private final DailyRepo repo = new DailyRepo(AppConfig.getDbPath());

	// Period A
	private final ComboBox<Integer> cbYearA = new ComboBox<>();
	private final ComboBox<Integer> cbMonthA = new ComboBox<>();
	private final Button btnPrevA = new Button("◀");
	private final Button btnNextA = new Button("▶");
	private final Label lblMonthA = new Label(); // dynamic month title

	// Period B
	private final ComboBox<Integer> cbYearB = new ComboBox<>();
	private final ComboBox<Integer> cbMonthB = new ComboBox<>();
	private final Button btnPrevB = new Button("◀");
	private final Button btnNextB = new Button("▶");
	private final Label lblMonthB = new Label(); // dynamic month title

	private final CheckBox chkPercent = new CheckBox("Percent mode (share of month)");
	private final Button btnRefresh = new Button("Refresh");
	private final Button btnSwap = new Button("Swap");
	private final Button btnExport = new Button("Export CSV");

	// Table
	private final TableView<Row> table = new TableView<>();

	// Chart
	private final CategoryAxis x = new CategoryAxis();
	private final NumberAxis y = new NumberAxis();
	private final BarChart<String, Number> chart = new BarChart<>(x, y);

	// dynamic column headers
	private final TableColumn<Row, String> cCat = new TableColumn<>("Category");
	private final TableColumn<Row, String> cA = new TableColumn<>();
	private final TableColumn<Row, String> cB = new TableColumn<>();
	private final TableColumn<Row, String> cD = new TableColumn<>("Δ (B - A)");
	private final TableColumn<Row, String> cPct = new TableColumn<>("% Change");

	private final DecimalFormat INT = new DecimalFormat("#,##0");
	private final DecimalFormat PCT = new DecimalFormat("0.0%");

	public CompareMonthsView()
	{
		buildHeader();
		buildTable();
		buildChart();

		SplitPane split = new SplitPane(table, chart);
		split.setDividerPositions(0.48);
		setCenter(split);

		reload();
	}

	/* ================= Header ================= */

	private void buildHeader()
	{
		int thisYear = LocalDate.now().getYear();
		for (int y = thisYear - 6; y <= thisYear + 1; y++)
		{
			cbYearA.getItems().add(y);
			cbYearB.getItems().add(y);
		}
		cbYearA.setValue(thisYear);
		cbYearB.setValue(thisYear);

		for (int m = 1; m <= 12; m++)
		{
			cbMonthA.getItems().add(m);
			cbMonthB.getItems().add(m);
		}
		cbMonthA.setValue(LocalDate.now().getMonthValue());
		cbMonthB.setValue(Math.max(1, LocalDate.now().getMonthValue() - 1));

		int h = 30;
		Arrays.asList(cbYearA, cbMonthA, cbYearB, cbMonthB, btnPrevA, btnNextA, btnPrevB, btnNextB, chkPercent, btnRefresh, btnSwap, btnExport).forEach(c ->
		{
			if (c instanceof Control cc) cc.setPrefHeight(h);
		});

		// A block (now shows month name)
		HBox aBlock = new HBox(6, new Label("Month 1:"), btnPrevA, cbYearA, cbMonthA, btnNextA, lblMonthA);
		aBlock.setAlignment(Pos.CENTER_LEFT);
		// B block
		HBox bBlock = new HBox(6, new Label("Month 2:"), btnPrevB, cbYearB, cbMonthB, btnNextB, lblMonthB);
		bBlock.setAlignment(Pos.CENTER_LEFT);

		HBox row = new HBox(12, aBlock, bBlock, chkPercent, btnRefresh, btnSwap, btnExport);
		row.setAlignment(Pos.CENTER_LEFT);
		row.setPadding(new Insets(8, 10, 8, 10));
		setTop(row);

		// Events
		btnPrevA.setOnAction(e -> stepMonth(cbYearA, cbMonthA, -1));
		btnNextA.setOnAction(e -> stepMonth(cbYearA, cbMonthA, +1));
		btnPrevB.setOnAction(e -> stepMonth(cbYearB, cbMonthB, -1));
		btnNextB.setOnAction(e -> stepMonth(cbYearB, cbMonthB, +1));

		btnSwap.setOnAction(e ->
		{
			Integer ya = cbYearA.getValue(), yb = cbYearB.getValue();
			Integer ma = cbMonthA.getValue(), mb = cbMonthB.getValue();
			cbYearA.setValue(yb);
			cbMonthA.setValue(mb);
			cbYearB.setValue(ya);
			cbMonthB.setValue(ma);
		});

		btnRefresh.setOnAction(e -> reload());
		btnExport.setOnAction(e -> exportCsv());

		cbYearA.valueProperty().addListener((o, a, b) -> reload());
		cbMonthA.valueProperty().addListener((o, a, b) -> reload());
		cbYearB.valueProperty().addListener((o, a, b) -> reload());
		cbMonthB.valueProperty().addListener((o, a, b) -> reload());
		chkPercent.selectedProperty().addListener((o, a, b) -> reload());
	}

	private void stepMonth(ComboBox<Integer> year, ComboBox<Integer> month, int delta)
	{
		YearMonth ym = YearMonth.of(year.getValue(), month.getValue()).plusMonths(delta);
		year.setValue(ym.getYear());
		month.setValue(ym.getMonthValue());
	}

	/* ================= Table ================= */

	private void buildTable()
	{
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		table.setFixedCellSize(26);
		table.setStyle("-fx-font-size: 13.5px; -fx-font-family: 'Segoe UI','Helvetica Neue',Arial;");

		cCat.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().category));

		cA.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(fmt(cd.getValue().a)));
		cB.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(fmt(cd.getValue().b)));

		cD.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(fmt(cd.getValue().delta)));
		cD.setCellFactory(col -> new TableCell<>()
		{
			@Override
			protected void updateItem(String v, boolean empty)
			{
				super.updateItem(v, empty);
				if (empty || v == null)
				{
					setText("");
					setStyle("");
					return;
				}
				setText(v);
				Row r = getTableView().getItems().get(getIndex());
				double d = r.delta;
				String color = d > 0 ? "#1b8d36" : (d < 0 ? "#b31b1b" : "-fx-text-base-color");
				setStyle("-fx-font-weight: 700; -fx-text-fill: " + (d == 0 ? "-fx-text-base-color" : color) + ";");
			}
		});

		cPct.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(pct(cd.getValue().a, cd.getValue().b)));
		cPct.setCellFactory(col -> new TableCell<>()
		{
			@Override
			protected void updateItem(String v, boolean empty)
			{
				super.updateItem(v, empty);
				if (empty || v == null)
				{
					setText("");
					setStyle("");
					return;
				}
				setText(v);
				Row r = getTableView().getItems().get(getIndex());
				double p = pctVal(r.a, r.b);
				String color = p > 0 ? "#1b8d36" : (p < 0 ? "#b31b1b" : "-fx-text-base-color");
				setStyle("-fx-font-weight: 700; -fx-text-fill: " + (p == 0 ? "-fx-text-base-color" : color) + ";");
			}
		});

		table.getColumns().setAll(cCat, cA, cB, cD, cPct);
		updateDynamicHeaders(); // set initial column titles
	}

	/* ================= Chart ================= */

	private void buildChart()
	{
		chart.setAnimated(false);
		chart.setLegendVisible(true);
		chart.setCategoryGap(12);
		chart.setBarGap(4);
	}

	/* ================= Data / reload ================= */

	private void reload()
	{

		YearMonth A = YearMonth.of(cbYearA.getValue(), cbMonthA.getValue());
		YearMonth B = YearMonth.of(cbYearB.getValue(), cbMonthB.getValue());
		Map<String, Double> a = repo.monthTotals(A);
		Map<String, Double> b = repo.monthTotals(B);

		boolean percentMode = chkPercent.isSelected();
		double sumA = a.getOrDefault("Total", 0.0);
		double sumB = b.getOrDefault("Total", 0.0);
		List<Row> rows = new ArrayList<>();
		for (String cat : orderedCategories())
		{
			double va = a.getOrDefault(cat, 0.0);
			double vb = b.getOrDefault(cat, 0.0);
			if (percentMode)
			{
				va = (sumA > 0 ? (100.0 * va / sumA) : 0.0);
				vb = (sumB > 0 ? (100.0 * vb / sumB) : 0.0);
			}
			rows.add(new Row(cat, va, vb));
		}
		table.getItems().setAll(rows);

		rebuildChart(A, B, rows, percentMode);
		updateDynamicHeaders(); // refresh column titles and header labels
	}

	private void rebuildChart(YearMonth A, YearMonth B, List<Row> rows, boolean percentMode)
	{
		chart.getData().clear();

		String titleA = monthTitle(A);
		String titleB = monthTitle(B);

		XYChart.Series<String, Number> sA = new XYChart.Series<>();
		sA.setName(titleA);
		XYChart.Series<String, Number> sB = new XYChart.Series<>();
		sB.setName(titleB);

		List<String> cats = new ArrayList<>();
		for (Row r : rows)
		{
			cats.add(r.category);
			sA.getData().add(new XYChart.Data<>(r.category, roundForChart(r.a, percentMode)));
			sB.getData().add(new XYChart.Data<>(r.category, roundForChart(r.b, percentMode)));
		}
		x.setCategories(FXCollections.observableArrayList(cats));
		chart.getData().addAll(sA, sB);

		chart.setTitle("Month vs Month — " + titleA + " vs " + titleB);

		Platform.runLater(() ->
		{
			installTooltips(sA, percentMode);
			installTooltips(sB, percentMode);
			enableLegendToggle();
			recomputeYAxis(percentMode);
		});
	}

	/* ================= Dynamic labels ================= */

	private void updateDynamicHeaders()
	{
		YearMonth A = YearMonth.of(cbYearA.getValue(), cbMonthA.getValue());
		YearMonth B = YearMonth.of(cbYearB.getValue(), cbMonthB.getValue());
		String titleA = monthTitle(A);
		String titleB = monthTitle(B);

		// table headers
		cA.setText(titleA);
		cB.setText(titleB);

		// header labels next to controls
		lblMonthA.setText("⟵ " + titleA);
		lblMonthB.setText("⟵ " + titleB);
	}

	/* ================= Utilities ================= */

	private void installTooltips(XYChart.Series<String, Number> s, boolean percentMode)
	{
		for (XYChart.Data<String, Number> d : s.getData())
		{
			Node n = d.getNode();
			if (n == null) continue;
			String val = percentMode ? String.format(java.util.Locale.ENGLISH, "%.1f%%", d.getYValue().doubleValue()) : INT.format(d.getYValue().longValue());
			Tooltip.install(n, new Tooltip(s.getName() + " — " + d.getXValue() + ": " + val));
		}
	}

	private void enableLegendToggle()
	{
		for (Node item : chart.lookupAll(".chart-legend-item"))
		{
			Label lab = (Label) item.lookup(".label");
			if (lab == null) continue;
			item.setCursor(Cursor.HAND);
			item.setOnMouseClicked(ev ->
			{
				String name = lab.getText();
				for (XYChart.Series<String, Number> s : chart.getData())
				{
					if (Objects.equals(s.getName(), name))
					{
						boolean visible = s.getNode() == null || s.getNode().isVisible();
						if (s.getNode() != null) s.getNode().setVisible(!visible);
						for (XYChart.Data<String, Number> d : s.getData())
						{
							if (d.getNode() != null) d.getNode().setVisible(!visible);
						}
						item.setOpacity(visible ? 0.35 : 1.0);
						recomputeYAxis(chkPercent.isSelected());
						break;
					}
				}
			});
		}
	}

	private void recomputeYAxis(boolean percentMode)
	{
		if (percentMode)
		{
			y.setAutoRanging(false);
			y.setLowerBound(0);
			y.setUpperBound(100);
			y.setTickUnit(10);
			return;
		}
		double max = 0.0;
		for (XYChart.Series<String, Number> s : chart.getData())
		{
			boolean sVis = s.getNode() == null || s.getNode().isVisible();
			if (!sVis) continue;
			for (XYChart.Data<String, Number> d : s.getData())
			{
				boolean pVis = d.getNode() == null || d.getNode().isVisible();
				if (!pVis) continue;
				if (d.getYValue() != null) max = Math.max(max, d.getYValue().doubleValue());
			}
		}
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

	private List<String> orderedCategories()
	{
		return List.of("Terrain", "Padel", "Gym", "Park", "Mini Golf", "Ping Pong", "Academy", "Taekwondo", "Shoes", "Drinks", "Cash", "Card", "Cheque", "Total");
	}

	private String monthTitle(YearMonth ym)
	{
		return ym.getMonth().getDisplayName(TextStyle.FULL, java.util.Locale.ENGLISH) + " " + ym.getYear();
	}

	private String fmt(double v)
	{
		return chkPercent.isSelected() ? String.format(java.util.Locale.ENGLISH, "%.1f%%", v) : INT.format(Math.round(v));
	}

	private String pct(double a, double b)
	{
		if (a == 0 && b == 0) return "0.0%";
		if (a == 0) return "∞";
		double p = (b - a) / a;
		return PCT.format(p);
	}

	private double pctVal(double a, double b)
	{
		if (a == 0) return b == 0 ? 0 : 1;
		return (b - a) / a;
	}

	private Number roundForChart(double v, boolean percent)
	{
		return percent ? Math.round(v * 10.0) / 10.0 : Math.round(v);
	}

	private void exportCsv()
	{
		try
		{
			YearMonth A = YearMonth.of(cbYearA.getValue(), cbMonthA.getValue());
			YearMonth B = YearMonth.of(cbYearB.getValue(), cbMonthB.getValue());
			boolean percentMode = chkPercent.isSelected();

			Path out = Path.of("compare-months_" + A + "_vs_" + B + (percentMode ? "_percent" : "") + ".csv");
			try (BufferedWriter bw = Files.newBufferedWriter(out))
			{
				bw.write("Category," + monthTitle(A) + "," + monthTitle(B) + ",Delta (B-A),% Change");
				bw.newLine();
				for (Row r : table.getItems())
				{
					String pct = pct(r.a, r.b);
					String a = percentMode ? String.format(java.util.Locale.ENGLISH, "%.1f", r.a) : Long.toString(Math.round(r.a));
					String b = percentMode ? String.format(java.util.Locale.ENGLISH, "%.1f", r.b) : Long.toString(Math.round(r.b));
					String d = percentMode ? String.format(java.util.Locale.ENGLISH, "%.1f", r.delta) : Long.toString(Math.round(r.delta));
					bw.write(r.category + "," + a + "," + b + "," + d + "," + pct);
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

	static class Row
	{
		final String category;
		final double a;
		final double b;
		final double delta;

		Row(String category, double a, double b)
		{
			this.category = category;
			this.a = a;
			this.b = b;
			this.delta = b - a;
		}
	}
}
