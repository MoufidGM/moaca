package com.cslsm.ui;

import com.cslsm.model.DailySummary;
import com.cslsm.repo.DailyRepo;
import com.cslsm.service.ImportService;
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
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;
import java.util.function.Function;

public class DailyView extends BorderPane
{

	private final DailyRepo repo = new DailyRepo(AppConfig.getDbPath());
	private final ImportService importService = new ImportService();
	private final HostServices host;

	// Top controls
	private final Label title = new Label("DAILY SUMMARY");
	private final DatePicker dp = new DatePicker(LocalDate.now());
	private final Button btnPrev = new Button("◀ Previous");
	private final Button btnNext = new Button("Next ▶");
	private final Button btnImport = new Button("Import Excel..");
	private final Button btnExport = new Button("Export CSV");
	private final Hyperlink linkOpenFile = new Hyperlink("Open file");

	private final Label lblTotals = new Label("TOTAL TTC: 0");


	private final Label lblCheque = new Label("Cheque: 0");

	private final Label lblCash = new Label("Cash: 0");
	private final Label lblCard = new Label("Card: 0");
	// Center: table + chart
	private final TableView<Row> table = new TableView<>();
	private final BarChart<String, Number> chart;

	// Misc
	private final DecimalFormat INT = new DecimalFormat("#,##0");
	private String currentFilePath = null;  // set after load; link uses this path

	public DailyView(HostServices hostServices)
	{
		this.host = hostServices;

		/* -------- Title row (centered) -------- */
		title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 20));

		lblTotals.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 22));
		lblTotals.setStyle("-fx-text-fill: -fx-text-base-color;");


		lblCash.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
		lblCard.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
		lblCheque.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));

		Region spacerL = new Region();
		Region spacerR = new Region();
		HBox.setHgrow(spacerL, Priority.ALWAYS);
		HBox.setHgrow(spacerR, Priority.ALWAYS);


		HBox headerTotals = new HBox(20, lblCash, lblCard, lblCheque, lblTotals);
		headerTotals.setAlignment(Pos.CENTER_LEFT);

		HBox titleRow = new HBox(10, spacerL, title, spacerR, headerTotals);
		titleRow.setAlignment(Pos.CENTER);
		titleRow.setPadding(new Insets(8, 10, 0, 10));

		/* -------- Controls row (well aligned) -------- */
		// Make controls the same visual height for neat alignment
		dp.setPrefHeight(30);
		btnPrev.setPrefHeight(30);
		btnNext.setPrefHeight(30);
		btnImport.setPrefHeight(30);
		btnExport.setPrefHeight(30);
		linkOpenFile.setVisited(false); // keep link from turning purple

		// Left-to-right: Date, picker, prev/next, Import, Export, Open file
		HBox ctrlRow = new HBox(10, new Label("Date:"), dp, btnPrev, btnNext, btnImport, btnExport, linkOpenFile);
		ctrlRow.setAlignment(Pos.CENTER_LEFT);
		ctrlRow.setPadding(new Insets(4, 10, 8, 10));

		VBox top = new VBox(titleRow, ctrlRow);
		setTop(top);

		/* -------- Table (bigger font) -------- */
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		table.setFixedCellSize(28);
		table.setStyle("-fx-font-size: 15px; -fx-font-family: 'Segoe UI','Helvetica Neue',Arial;");

		TableColumn<Row, String> cCat = textCol("Category", r -> r.label);
		TableColumn<Row, Number> cAmt = numCol("Amount", r -> r.amount);
		table.getColumns().setAll(cCat, cAmt);

		/* -------- Chart (dark blue) -------- */
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
		chart.setLegendVisible(false);
		chart.setCategoryGap(10);
		chart.setBarGap(3);
		chart.setStyle("-fx-font-size: 12px;");

		// Layout center
		VBox center = new VBox(6, table, chart);
		center.setPadding(new Insets(6, 10, 10, 10));
		VBox.setVgrow(chart, Priority.ALWAYS);
		setCenter(center);

		/* -------- Events -------- */
		dp.valueProperty().addListener((o, a, b) -> reload());
		btnPrev.setOnAction(e ->
		{
			LocalDate d = dp.getValue() != null ? dp.getValue() : LocalDate.now();
			dp.setValue(d.minusDays(1));
		});
		btnNext.setOnAction(e ->
		{
			LocalDate d = dp.getValue() != null ? dp.getValue() : LocalDate.now();
			dp.setValue(d.plusDays(1));
		});

		btnImport.setOnAction(e -> doImport());
		btnExport.setOnAction(e -> doExport());
		linkOpenFile.setOnAction(e -> openCurrentFile());
		updateOpenFileLinkState(); // disabled until a file is available

		reload();
	}

	/* ===================== UI helpers ===================== */

	private TableColumn<Row, String> textCol(String title, Function<Row, String> f)
	{
		TableColumn<Row, String> c = new TableColumn<>(title);
		c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue())));
		return c;
	}

	private TableColumn<Row, Number> numCol(String title, Function<Row, Double> f)
	{
		TableColumn<Row, Number> c = new TableColumn<>(title);
		c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue())));
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
				// make the "Daily Total" row visually stronger
				Row r = (Row) getTableRow().getItem();
				if (r != null && "Daily Total".equalsIgnoreCase(r.label))
				{
					setStyle("-fx-font-weight: 800;");
				}
				else
				{
					setStyle("");
				}
			}
		});
		return c;
	}

	/* ===================== Data + chart ===================== */

	private void reload()
	{
		LocalDate d = dp.getValue() != null ? dp.getValue() : LocalDate.now();

		// Title: "Monday 28 Jul 2025"
		Locale loc = Locale.ENGLISH;
		String nice = d.getDayOfWeek().getDisplayName(TextStyle.FULL, loc) + " " + d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.SHORT, loc) + " " + d.getYear();
		title.setText(nice);

		// Load summary for that date
		DailySummary s = null;
		try
		{
			s = repo.summaryFor(d).orElse(null);
		}
		catch (Exception ignored)
		{
		}

		currentFilePath = (s != null ? s.getFilePath() : null);
		updateOpenFileLinkState();

		List<Row> rows = new ArrayList<>();
		if (s != null)
		{
			rows.add(new Row("Terrain", nz(s.getTotalTerrain())));
			rows.add(new Row("Academy", nz(s.getTotalAcademyFoot())));
			rows.add(new Row("Gym", nz(s.getTotalGym())));
			rows.add(new Row("Taekwondo", nz(s.getTotalTaekwondo())));
			rows.add(new Row("Padel", nz(s.getTotalPadel())));
			rows.add(new Row("Park", nz(s.getTotalPark())));
			rows.add(new Row("Mini Golf", nz(s.getTotalMiniGolf())));
			rows.add(new Row("Ping Pong", nz(s.getTotalPingPong())));
			rows.add(new Row("Shoes", nz(s.getTotalShoes())));
			rows.add(new Row("Drinks", nz(s.getDrinksAmountTotal())));
			rows.add(new Row("Daily Total", nz(s.getTotalTtc())));
		}
		else
		{
			// blank day
			rows.add(new Row("Terrain", 0));
			rows.add(new Row("Academy", 0));
			rows.add(new Row("Gym", 0));
			rows.add(new Row("Taekwondo", 0));
			rows.add(new Row("Padel", 0));
			rows.add(new Row("Park", 0));
			rows.add(new Row("Mini Golf", 0));
			rows.add(new Row("Ping Pong", 0));
			rows.add(new Row("Shoes", 0));
			rows.add(new Row("Drinks", 0));
			rows.add(new Row("Daily Total", 0));
		}

		table.getItems().setAll(rows);
		sizeTableToRows();

		// Total label
		double total = rows.stream().filter(r -> "Daily Total".equalsIgnoreCase(r.label)).map(r -> r.amount).findFirst().orElse(0.0);
		//double cash = rows.stream().filter(r -> "Daily Total".equalsIgnoreCase(r.label)).map(r -> r.amount).findFirst().orElse(0.0);
		//double card = rows.stream().filter(r -> "Daily Total".equalsIgnoreCase(r.label)).map(r -> r.amount).findFirst().orElse(0.0);
		//double cheque = rows.stream().filter(r -> "Daily Total".equalsIgnoreCase(r.label)).map(r -> r.amount).findFirst().orElse(0.0);
		lblTotals.setText("TOTAL TTC: " + INT.format(Math.round(total)));

		if (s != null) refreshPaymentHeader(s);
		rebuildChart(rows);
	}

	private void rebuildChart(List<Row> rows)
	{
		chart.getData().clear();

		// Build one series (omit the grand total so bars match categories)
		XYChart.Series<String, Number> series = new XYChart.Series<>();
		for (Row r : rows)
		{
			if ("Daily Total".equalsIgnoreCase(r.label)) continue;
			if ("Cash".equalsIgnoreCase(r.label)) continue;
			if ("Card".equalsIgnoreCase(r.label)) continue;
			series.getData().add(new XYChart.Data<>(r.label, Math.round(r.amount)));
		}
		chart.getData().add(series);

		// Force bar fill to dark blue (#0b3d91) and add tooltips
		Platform.runLater(() ->
		{
			for (XYChart.Data<String, Number> d : series.getData())
			{
				Node n = d.getNode();
				if (n != null)
				{
					n.setStyle("-fx-bar-fill: #0b3d91;");
					Tooltip.install(n, new Tooltip(d.getXValue() + ": " + INT.format(d.getYValue().longValue())));
				}
			}
		});
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

	private void openCurrentFile()
	{
		try
		{
			if (currentFilePath == null || currentFilePath.isBlank()) return;
			Path p = Path.of(currentFilePath);
			if (Files.exists(p))
			{
				host.showDocument(p.toUri().toString());
			}
			else
			{
				new Alert(Alert.AlertType.WARNING, "File not found:\n" + p, ButtonType.OK).showAndWait();
			}
		}
		catch (Exception ex)
		{
			new Alert(Alert.AlertType.ERROR, "Could not open file: " + ex.getMessage(), ButtonType.OK).showAndWait();
		}
	}

	private void updateOpenFileLinkState()
	{
		boolean hasFile = false;
		try
		{
			hasFile = currentFilePath != null && !currentFilePath.isBlank() && Files.exists(Path.of(currentFilePath));
		}
		catch (Exception ignored)
		{
		}
		linkOpenFile.setDisable(!hasFile);
		linkOpenFile.setCursor(hasFile ? Cursor.HAND : Cursor.DEFAULT);
		linkOpenFile.setTooltip(new Tooltip(hasFile ? currentFilePath : "No file for this day"));
	}

	/* ===================== Import / Export ===================== */

	private void doExport()
	{
		try
		{
			LocalDate d = dp.getValue();
			if (d == null) return;
			Path out = Path.of("daily-" + d + ".csv");
			try (BufferedWriter bw = Files.newBufferedWriter(out))
			{
				bw.write("Category,Amount");
				bw.newLine();
				for (Row r : table.getItems())
				{
					bw.write(r.label + "," + Math.round(r.amount));
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

	private void doImport()
	{
		// 1) Choose file
		final Path chosen;
		try
		{
			FileChooser fc = new FileChooser();
			fc.setTitle("Select Daily Log Excel");
			fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel", "*.xlsx", "*.xls"));
			var file = fc.showOpenDialog(getScene() == null ? null : getScene().getWindow());
			if (file == null) return;
			chosen = file.toPath();
		}
		catch (Exception ex)
		{
			new Alert(Alert.AlertType.ERROR, "File chooser error: " + ex.getMessage(), ButtonType.OK).showAndWait();
			return;
		}

		// 2) Probe (no DB writes). If broken/incompatible, just show popup and stop.
		ImportService.ProbeResult pr = importService.probe(chosen);
		if (!pr.ok)
		{
			new Alert(Alert.AlertType.ERROR, "This file could not be read and was NOT imported.\n\nReason: " + pr.message, ButtonType.OK).showAndWait();
			return;
		}

		// 3) Duplicate? Compare and ask the user.
		if (pr.duplicate)
		{
			Optional<ButtonType> ans = showDuplicateDialog(pr.existing, pr.parsed);
			if (ans.isEmpty() || ans.get().getButtonData() != ButtonBar.ButtonData.OK_DONE)
			{
				new Alert(Alert.AlertType.INFORMATION, "Keeping existing entry for " + pr.date + ".", ButtonType.OK).showAndWait();
				return;
			}
		}

		// 4) Proceed with real import (move + upsert)
		String msg = importService.processOne(chosen);
		if (msg.startsWith("Imported"))
		{
			new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK).showAndWait();
			// Jump the date picker to the imported day from filename
			dp.setValue(extractDateFromFilename(chosen.getFileName().toString()));
			reload();
		}
		else
		{
			new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait();
		}
	}

	private Optional<ButtonType> showDuplicateDialog(DailySummary oldSum, DailySummary newSum)
	{
		Dialog<ButtonType> dlg = new Dialog<>();
		dlg.setTitle("Duplicate day detected");
		dlg.getDialogPane().getButtonTypes().addAll(new ButtonType("Replace with New", ButtonBar.ButtonData.OK_DONE), new ButtonType("Keep Existing", ButtonBar.ButtonData.CANCEL_CLOSE));

		GridPane grid = new GridPane();
		grid.setHgap(12);
		grid.setVgap(6);
		grid.setPadding(new Insets(10));

		grid.add(new Label("Field"), 0, 0);
		grid.add(new Label("Existing"), 1, 0);
		grid.add(new Label("New"), 2, 0);

		int r = 1;
		for (Map.Entry<String, Double[]> e : comparePairs(oldSum, newSum).entrySet())
		{
			grid.add(new Label(e.getKey()), 0, r);
			grid.add(new Label(INT.format(Math.round(e.getValue()[0]))), 1, r);
			grid.add(new Label(INT.format(Math.round(e.getValue()[1]))), 2, r);
			r++;
		}

		dlg.getDialogPane().setContent(grid);
		return dlg.showAndWait();
	}
	private void refreshPaymentHeader(DailySummary s) {
		try {
			if (s != null) {
				if (lblCash   != null) lblCash.setText("Cash: "   + INT.format(Math.round(s.getTotalCash())));
				if (lblCard   != null) lblCard.setText("Card: "   + INT.format(Math.round(s.getTotalCard())));
				if (lblCheque != null) lblCheque.setText("Cheque: " + INT.format(Math.round(s.getTotalCheque())));
			}
		} catch (Exception ignored) {}
	}


	private Map<String, Double[]> comparePairs(DailySummary oldS, DailySummary newS)
	{
		LinkedHashMap<String, Double[]> m = new LinkedHashMap<>();
		m.put("Terrain", pair(oldS.getTotalTerrain(), newS.getTotalTerrain()));
		m.put("Academy", pair(oldS.getTotalAcademyFoot(), newS.getTotalAcademyFoot()));
		m.put("Gym", pair(oldS.getTotalGym(), newS.getTotalGym()));
		m.put("Taekwondo", pair(oldS.getTotalTaekwondo(), newS.getTotalTaekwondo()));
		m.put("Padel", pair(oldS.getTotalPadel(), newS.getTotalPadel()));
		m.put("Park", pair(oldS.getTotalPark(), newS.getTotalPark()));
		m.put("Mini Golf", pair(oldS.getTotalMiniGolf(), newS.getTotalMiniGolf()));
		m.put("Ping Pong", pair(oldS.getTotalPingPong(), newS.getTotalPingPong()));
		m.put("Shoes", pair(oldS.getTotalShoes(), newS.getTotalShoes()));
		m.put("Drinks", pair(oldS.getDrinksAmountTotal(), newS.getDrinksAmountTotal()));
		m.put("Daily Total", pair(oldS.getTotalTtc(), newS.getTotalTtc()));
		return m;
	}

	private Double[] pair(Double a, Double b)
	{
		return new Double[]{nz(a), nz(b)};
	}

	private LocalDate extractDateFromFilename(String name)
	{
		// expects DL-dd-MM-yyyy.xlsx (or .xls)
		try
		{
			String[] parts = name.split("-");
			int dd = Integer.parseInt(parts[1]);
			int mm = Integer.parseInt(parts[2]);
			int yy = Integer.parseInt(parts[3].substring(0, 4));
			return LocalDate.of(yy, mm, dd);
		}
		catch (Exception e)
		{
			return dp.getValue();
		}
	}

	/* ===================== Table row model ===================== */
	private class Row
	{
		final String label;
		final double amount;

		Row(String label, double amount)
		{
			this.label = label;
			this.amount = amount;
		}


	}
}

