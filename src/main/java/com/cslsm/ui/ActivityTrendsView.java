package com.cslsm.ui;

import com.cslsm.repo.DailyRepo;
import com.cslsm.util.AppConfig;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

public class ActivityTrendsView extends BorderPane
{

	private final DailyRepo repo = new DailyRepo(AppConfig.getDbPath());

	private final ComboBox<String> cbActivity = new ComboBox<>();
	private final ComboBox<String> cbGranularity = new ComboBox<>();
	private final DatePicker dpFrom = new DatePicker(LocalDate.now().minusMonths(1));
	private final DatePicker dpTo = new DatePicker(LocalDate.now());
	private final CheckBox chkMA7 = new CheckBox("Moving average (7d)");
	private final Button btnRefresh = new Button("Refresh");
	private final Button btnExport = new Button("Export CSV");

	private final CategoryAxis x = new CategoryAxis();
	private final NumberAxis y = new NumberAxis();
	private final LineChart<String, Number> chart = new LineChart<>(x, y);

	private final DecimalFormat INT = new DecimalFormat("#,##0");

	public ActivityTrendsView()
	{
		cbActivity.getItems().addAll(DailyRepo.FIELD_BY_ACTIVITY.keySet());
		cbActivity.setValue("Daily Total");

		cbGranularity.getItems().addAll("Daily", "Monthly");
		cbGranularity.setValue("Daily");

		int h = 30;
		Arrays.asList(cbActivity, cbGranularity, dpFrom, dpTo, chkMA7, btnRefresh, btnExport).forEach(c ->
		{
			if (c instanceof Control cc) cc.setPrefHeight(h);
		});

		HBox ctrl = new HBox(10, new Label("Activity:"), cbActivity, new Label("Plot:"), cbGranularity, new Label("From:"), dpFrom, new Label("To:"), dpTo, chkMA7, btnRefresh, btnExport);
		ctrl.setAlignment(Pos.CENTER_LEFT);
		ctrl.setPadding(new Insets(8, 10, 8, 10));

		chart.setAnimated(false);
		chart.setLegendVisible(true);
		chart.setCreateSymbols(true);

		setTop(ctrl);
		setCenter(chart);

		btnRefresh.setOnAction(e -> reload());
		btnExport.setOnAction(e -> exportCsv());
		cbActivity.valueProperty().addListener((o, a, b) -> reload());
		cbGranularity.valueProperty().addListener((o, a, b) ->
		{
			chkMA7.setDisable("Monthly".equals(b)); // moving average only makes sense for daily points
			reload();
		});
		dpFrom.valueProperty().addListener((o, a, b) -> reload());
		dpTo.valueProperty().addListener((o, a, b) -> reload());
		chkMA7.selectedProperty().addListener((o, a, b) -> reload());

		reload();
	}

	private void reload()
	{
		String label = cbActivity.getValue();
		String field = DailyRepo.FIELD_BY_ACTIVITY.get(label);
		LocalDate from = dpFrom.getValue(), to = dpTo.getValue();
		if (field == null || from == null || to == null || from.isAfter(to)) return;

		Map<String, Double> data = seriesData(field, from, to);
		boolean monthly = "Monthly".equals(cbGranularity.getValue());

		chart.getData().clear();

		XYChart.Series<String, Number> s = new XYChart.Series<>();
		s.setName(label + (monthly ? " (monthly)" : ""));
		for (var e : data.entrySet())
		{
			s.getData().add(new XYChart.Data<>(e.getKey(), Math.round(e.getValue())));
		}
		chart.getData().add(s);

		if (!monthly && chkMA7.isSelected() && data.size() > 0)
		{
			XYChart.Series<String, Number> ma = new XYChart.Series<>();
			ma.setName("MA(7)");
			var vals = new ArrayList<>(data.values());
			var dates = new ArrayList<>(data.keySet());
			for (int i = 0; i < vals.size(); i++)
			{
				int a = Math.max(0, i - 6), b = i;
				double sum = 0;
                for (int j = a; j <= b; j++)
                {
                    sum += vals.get(j);
                }
				double avg = sum / (b - a + 1);
				ma.getData().add(new XYChart.Data<>(dates.get(i), Math.round(avg)));
			}
			chart.getData().add(ma);
		}

		Platform.runLater(() ->
		{
			installTooltips();
			enableLegendToggle();
			recomputeYAxis();
		});
	}

	/**
	 * Series points for the current granularity:
	 * Daily -> one point per day ("yyyy-MM-dd"), Monthly -> summed per month ("yyyy-MM").
	 */
	private Map<String, Double> seriesData(String field, LocalDate from, LocalDate to)
	{
		Map<LocalDate, Double> daily = repo.activitySeries(field, from, to);
		Map<String, Double> out = new java.util.LinkedHashMap<>();
		boolean monthly = "Monthly".equals(cbGranularity.getValue());
		for (var e : daily.entrySet())
		{
			String key = monthly ? java.time.YearMonth.from(e.getKey()).toString() : e.getKey().toString();
			out.merge(key, e.getValue(), Double::sum);
		}
		return out;
	}

	private void installTooltips()
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
						recomputeYAxis();
						break;
					}
				}
			});
		}
	}

	/**
	 * Compute Y range using only visible series & points.
	 */
	private void recomputeYAxis()
	{
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

	private void exportCsv()
	{
		try
		{
			String label = cbActivity.getValue();
			String field = DailyRepo.FIELD_BY_ACTIVITY.get(label);
			if (field == null) return;

			LocalDate from = dpFrom.getValue(), to = dpTo.getValue();
			Map<String, Double> data = seriesData(field, from, to);
			boolean monthly = "Monthly".equals(cbGranularity.getValue());
			Path out = Path.of("activity-" + label.replace(' ', '_') + (monthly ? "-monthly" : "") + "-" + from + "_to_" + to + ".csv");
			try (BufferedWriter bw = Files.newBufferedWriter(out))
			{
				bw.write((monthly ? "month," : "date,") + label);
				bw.newLine();
				for (var e : data.entrySet())
				{
					bw.write(e.getKey() + "," + Math.round(e.getValue()));
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
}
