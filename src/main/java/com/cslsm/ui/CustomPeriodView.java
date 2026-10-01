package com.cslsm.ui;// imports (add those you don't already have)

import com.cslsm.model.DailySummary;
import com.cslsm.repo.DailyRepo;
import com.cslsm.util.AppConfig;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

// Put in com.cslsm.ui (or wherever you keep your views)
class CustomPeriodView extends BorderPane
{

	private final DailyRepo repo = new DailyRepo(AppConfig.getDbPath());

	private final DatePicker dpFrom = new DatePicker(LocalDate.now().withDayOfMonth(1));
	private final DatePicker dpTo = new DatePicker(LocalDate.now());

	private final Button btnRefresh = new Button("Refresh");
	private final Button btnExport = new Button("Export CSV"); // NEW
	private final Label lblRange = new Label();

	// Table
	private final TableView<DailySummary> table = new TableView<>();
	private final ObservableList<DailySummary> rows = FXCollections.observableArrayList();

	// Totals bar
	private final Label totalTerrain = new Label("0");
	private final Label totalAcademy = new Label("0");
	private final Label totalGym = new Label("0");
	private final Label totalTkd = new Label("0");
	private final Label totalPadel = new Label("0");
	private final Label totalPark = new Label("0");
	private final Label totalMiniGolf = new Label("0");
	private final Label totalPingPong = new Label("0");
	private final Label totalShoes = new Label("0");
	private final Label totalDrinks = new Label("0");
	private final Label totalCash = new Label("0");
	private final Label totalCard = new Label("0");
	private final Label totalTtc = new Label("0");

	// Charts
	private final TabPane chartsTabs = new TabPane();
	private final LineChart<String, Number> dailySeriesChart = new LineChart<>(new CategoryAxis(), new NumberAxis());

	CustomPeriodView()
	{

		buildTopBar();
		buildTable();
		buildCharts();

		setPadding(new Insets(10));
		setTop(buildHeader());
		setCenter(buildCenter());

		// Initial load
		refresh();
	}

	private static String fmt(double v)
	{
		return String.format(Locale.ENGLISH, "%,.0f", v);
	}

	private Node buildHeader()
	{
		btnExport.setDisable(true); // disabled until we have data

		HBox row1 = new HBox(12, new Label("From:"), dpFrom, new Label("To:"), dpTo, btnRefresh, btnExport           // NEW
		);
		row1.setAlignment(Pos.CENTER_LEFT);

		HBox row2 = new HBox(lblRange);
		row2.setAlignment(Pos.CENTER_LEFT);
		row2.setPadding(new Insets(4, 0, 0, 0));
		lblRange.setStyle("-fx-font-weight: bold;");

		VBox box = new VBox(6, row1, row2);
		box.setPadding(new Insets(10, 10, 10, 10));
		box.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #ddd; -fx-border-radius: 6; -fx-background-radius: 6;");
		return box;
	}

	private Node buildCenter()
	{
		Tab tableTab = new Tab("Summary Table", table);
		tableTab.setClosable(false);

		Tab chartTab = new Tab("Charts", chartsTabs);
		chartTab.setClosable(false);

		TabPane center = new TabPane(tableTab, chartTab);
		center.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
		return center;
	}

	private boolean isTotalRow(DailySummary s)
	{
		return s != null && s.getLogDate() != null && s.getLogDate().startsWith("TOTAL");
	}

	private DailySummary makeTotalsRow(List<DailySummary> list)
	{
		double terr = 0, acad = 0, gym = 0, tkd = 0, pad = 0, park = 0, mini = 0, ping = 0, shoes = 0, drinks = 0, ttc = 0, cash = 0, card = 0, chq = 0;
		for (DailySummary s : list)
		{
			terr += s.getTotalTerrain();
			acad += s.getTotalAcademyFoot();
			gym += s.getTotalGym();
			tkd += s.getTotalTaekwondo();
			pad += s.getTotalPadel();
			park += s.getTotalPark();
			mini += s.getTotalMiniGolf();
			ping += s.getTotalPingPong();
			shoes += s.getTotalShoes();
			drinks += s.getDrinksAmountTotal();
			ttc += s.getTotalTtc();
			cash += s.getTotalCash();
			card += s.getTotalCard();
			chq += s.getTotalCheque();
		}
		DailySummary total = new DailySummary();
		total.setLogDate("TOTAL");              // marker for styling & sorting
		total.setTotalTerrain(terr);
		total.setTotalAcademyFoot(acad);
		total.setTotalGym(gym);
		total.setTotalTaekwondo(tkd);
		total.setTotalPadel(pad);
		total.setTotalPark(park);
		total.setTotalMiniGolf(mini);
		total.setTotalPingPong(ping);
		total.setTotalShoes(shoes);
		total.setDrinksAmountTotal(drinks);
		total.setTotalTtc(ttc);
		total.setTotalCash(cash);
		total.setTotalCard(card);
		total.setTotalCheque(chq);
		return total;
	}

	private void setupTotalRowBehavior()
	{
		// Put the TOTAL row at the bottom regardless of column sorting.
		table.setSortPolicy(tv ->
		{
			rows.sort((a, b) ->
			{
				boolean ta = isTotalRow(a);
				boolean tb = isTotalRow(b);
				if (ta && !tb) return 1;
				if (tb && !ta) return -1;
				// default: date ascending (string "yyyy-MM-dd")
				return String.valueOf(a.getLogDate()).compareTo(String.valueOf(b.getLogDate()));
			});
			return true;
		});

		// Style the TOTAL row (bold + larger font)
		table.setRowFactory(tv -> new TableRow<>()
		{
			@Override
			protected void updateItem(DailySummary item, boolean empty)
			{
				super.updateItem(item, empty);
				if (empty || item == null)
				{
					setStyle("");
				}
				else if (isTotalRow(item))
				{
					setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
				}
				else
				{
					setStyle("");
				}
			}
		});
	}

	private void buildTopBar()
	{
		btnRefresh.setOnAction(e -> refresh());
		btnExport.setOnAction(e -> exportCsv()); // NEW

		// nice date format
		DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
		StringConverter<LocalDate> cvt = new StringConverter<>()
		{
			@Override
			public String toString(LocalDate d)
			{
				return d == null ? "" : d.format(fmt);
			}

			@Override
			public LocalDate fromString(String s)
			{
				return (s == null || s.isBlank()) ? null : LocalDate.parse(s, fmt);
			}
		};
		dpFrom.setConverter(cvt);
		dpTo.setConverter(cvt);
	}

	private void buildTable()
	{
		table.setItems(rows);
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		table.getColumns().add(col("Date", DailySummary::getLogDate));
		table.getColumns().addAll(numCol("Terrain", DailySummary::getTotalTerrain), numCol("Academy", DailySummary::getTotalAcademyFoot), numCol("Gym", DailySummary::getTotalGym), numCol("Taekwondo", DailySummary::getTotalTaekwondo), numCol("Padel", DailySummary::getTotalPadel), numCol("Park", DailySummary::getTotalPark), numCol("Mini Golf", DailySummary::getTotalMiniGolf), numCol("Ping Pong", DailySummary::getTotalPingPong), numCol("Shoes", DailySummary::getTotalShoes), numCol("Drinks", DailySummary::getDrinksAmountTotal), numCol("Cash", DailySummary::getTotalCash), numCol("Card", DailySummary::getTotalCard), numCol("Total TTC", DailySummary::getTotalTtc));

		setupTotalRowBehavior(); // <--- add this line
	}

	private TableColumn<DailySummary, String> col(String title, java.util.function.Function<DailySummary, String> getter)
	{
		TableColumn<DailySummary, String> c = new TableColumn<>(title);
		c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(getter.apply(cd.getValue())));
		return c;
	}

	private TableColumn<DailySummary, Number> numCol(String title, java.util.function.ToDoubleFunction<DailySummary> getter)
	{
		TableColumn<DailySummary, Number> c = new TableColumn<>(title);
		c.setCellValueFactory(cd -> new ReadOnlyDoubleWrapper(getter.applyAsDouble(cd.getValue())));
		return c;
	}

	private void buildCharts()
	{
		dailySeriesChart.setCreateSymbols(false);
		dailySeriesChart.setLegendVisible(true);
		dailySeriesChart.setAnimated(false);
		dailySeriesChart.getXAxis().setLabel("Date");
		dailySeriesChart.getYAxis().setLabel("Amount");

		Tab totalsLine = new Tab("Daily Totals", dailySeriesChart);
		totalsLine.setClosable(false);

		chartsTabs.getTabs().addAll(totalsLine);
		chartsTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
	}

	private Node buildTotalsBar()
	{
		GridPane g = new GridPane();
		g.setHgap(12);
		g.setVgap(6);
		g.setPadding(new Insets(8));

		int r = 0;
		addTotalRow(g, r++, "Terrain", totalTerrain);
		addTotalRow(g, r++, "Academy", totalAcademy);
		addTotalRow(g, r++, "Gym", totalGym);
		addTotalRow(g, r++, "Taekwondo", totalTkd);
		addTotalRow(g, r++, "Padel", totalPadel);
		addTotalRow(g, r++, "Park", totalPark);
		addTotalRow(g, r++, "Mini Golf", totalMiniGolf);
		addTotalRow(g, r++, "Ping Pong", totalPingPong);
		addTotalRow(g, r++, "Shoes", totalShoes);
		addTotalRow(g, r++, "Drinks", totalDrinks);
		addTotalRow(g, r++, "Cash", totalCash);
		addTotalRow(g, r++, "Card", totalCard);
		addTotalRow(g, r++, "Total TTC", totalTtc);

		g.setStyle("-fx-background-color:#fafafa; -fx-border-color:#e3e3e3; -fx-border-radius:6; -fx-background-radius:6;");
		return g;
	}

	private void addTotalRow(GridPane g, int row, String label, Label value)
	{
		Label l = new Label(label + ":");
		l.setStyle("-fx-font-weight: bold;");
		value.setMaxWidth(Double.MAX_VALUE);
		g.add(l, 0, row);
		g.add(value, 1, row);
	}

	private void refresh()
	{
		LocalDate from = dpFrom.getValue();
		LocalDate to = dpTo.getValue();
		if (from == null || to == null || to.isBefore(from))
		{
			lblRange.setText("Select a valid date range.");
			rows.clear();
			dailySeriesChart.getData().clear();
			btnExport.setDisable(true);
			return;
		}
		lblRange.setText("Range: " + from + " → " + to);

		CompletableFuture.supplyAsync(() -> repo.listBetween(from, to)).thenAccept(list -> Platform.runLater(() ->
		{
			// normal rows
			rows.setAll(list);
			// append TOTAL row
			rows.add(makeTotalsRow(list));

			updateCharts(list);                         // charts use per-day (exclude total)
			btnExport.setDisable(list.isEmpty());
			table.sort();                               // re-apply sortPolicy to keep TOTAL at bottom
		})).exceptionally(ex ->
		{
			Platform.runLater(() ->
			{
				rows.clear();
				dailySeriesChart.getData().clear();
				lblRange.setText("Error: " + ex.getMessage());
				btnExport.setDisable(true);
			});
			return null;
		});
	}

	private void clearTotals()
	{
		for (Label l : List.of(totalTerrain, totalAcademy, totalGym, totalTkd, totalPadel, totalPark, totalMiniGolf, totalPingPong, totalShoes, totalDrinks, totalTtc))
		{
			l.setText("0");
		}
	}

	private void updateTotals(List<DailySummary> list)
	{
		double terr = 0, acad = 0, gym = 0, tkd = 0, pad = 0, park = 0, mini = 0, ping = 0, shoes = 0, drinks = 0, ttc = 0;
		for (DailySummary s : list)
		{
			terr += s.getTotalTerrain();
			acad += s.getTotalAcademyFoot();
			gym += s.getTotalGym();
			tkd += s.getTotalTaekwondo();
			pad += s.getTotalPadel();
			park += s.getTotalPark();
			mini += s.getTotalMiniGolf();
			ping += s.getTotalPingPong();
			shoes += s.getTotalShoes();
			drinks += s.getDrinksAmountTotal();
			ttc += s.getTotalTtc();
		}
		totalTerrain.setText(fmt(terr));
		totalAcademy.setText(fmt(acad));
		totalGym.setText(fmt(gym));
		totalTkd.setText(fmt(tkd));
		totalPadel.setText(fmt(pad));
		totalPark.setText(fmt(park));
		totalMiniGolf.setText(fmt(mini));
		totalPingPong.setText(fmt(ping));
		totalShoes.setText(fmt(shoes));
		totalDrinks.setText(fmt(drinks));
		totalTtc.setText(fmt(ttc));
	}

	private void updateCharts(List<DailySummary> list)
	{
		dailySeriesChart.getData().clear();
		XYChart.Series<String, Number> seriesTotal = new XYChart.Series<>();
		seriesTotal.setName("Total TTC");
		XYChart.Series<String, Number> seriesTerrain = new XYChart.Series<>();
		seriesTerrain.setName("Terrain");

		for (DailySummary s : list)
		{
			String x = s.getLogDate();
			seriesTotal.getData().add(new XYChart.Data<>(x, s.getTotalTtc()));
			seriesTerrain.getData().add(new XYChart.Data<>(x, s.getTotalTerrain()));
		}
		dailySeriesChart.getData().addAll(seriesTotal, seriesTerrain);
	}

	// =========================
	// Export CSV (NEW)
	// =========================
	private void exportCsv()
	{
		if (rows.isEmpty()) return;

		FileChooser fc = new FileChooser();
		fc.setTitle("Export Custom Period");
		fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
		fc.setInitialFileName("custom-period-" + dpFrom.getValue() + "_to_" + dpTo.getValue() + ".csv");

		Path target;
		try
		{
			File f = fc.showSaveDialog(getScene() != null ? getScene().getWindow() : null);
			if (f == null) return;
			target = f.toPath();
		}
		catch (Exception e)
		{
			System.err.println("[CSLSM] Export canceled: " + e);
			return;
		}

		try (BufferedWriter bw = Files.newBufferedWriter(target, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
		{

			// Header
			bw.write(String.join(",", "Date", "Terrain", "Academy", "Gym", "Taekwondo", "Padel", "Park", "Mini Golf", "Ping Pong", "Shoes", "Drinks", "Total TTC", "Cash", "Card", "Cheque", "File Path"));
			bw.newLine();

			// Rows
			for (DailySummary s : rows)
			{
				bw.write(String.join(",", s.getLogDate(), d(s.getTotalTerrain()), d(s.getTotalAcademyFoot()), d(s.getTotalGym()), d(s.getTotalTaekwondo()), d(s.getTotalPadel()), d(s.getTotalPark()), d(s.getTotalMiniGolf()), d(s.getTotalPingPong()), d(s.getTotalShoes()), d(s.getDrinksAmountTotal()), d(s.getTotalTtc()), d(s.getTotalCash()), d(s.getTotalCard()), d(s.getTotalCheque()), csvEscape(s.getFilePath())));
				bw.newLine();
			}

			// Totals row (optional summary at end)
			bw.write(String.join(",", "TOTAL", d(sum(DailySummary::getTotalTerrain)), d(sum(DailySummary::getTotalAcademyFoot)), d(sum(DailySummary::getTotalGym)), d(sum(DailySummary::getTotalTaekwondo)), d(sum(DailySummary::getTotalPadel)), d(sum(DailySummary::getTotalPark)), d(sum(DailySummary::getTotalMiniGolf)), d(sum(DailySummary::getTotalPingPong)), d(sum(DailySummary::getTotalShoes)), d(sum(DailySummary::getDrinksAmountTotal)), d(sum(DailySummary::getTotalTtc)), d(sum(DailySummary::getTotalCash)), d(sum(DailySummary::getTotalCard)), d(sum(DailySummary::getTotalCheque)), ""));
			bw.newLine();

		}
		catch (IOException io)
		{
			System.err.println("[CSLSM] Export failed: " + io.getMessage());
		}
	}

	private String d(double v)
	{
		return String.format(Locale.ENGLISH, "%.2f", v);
	}

	private String csvEscape(String s)
	{
		if (s == null) return "";
		if (s.contains(",") || s.contains("\"") || s.contains("\n"))
		{
			return "\"" + s.replace("\"", "\"\"") + "\"";
		}
		return s;
	}

	private double sum(java.util.function.ToDoubleFunction<DailySummary> f)
	{
		double t = 0;
		for (DailySummary s : rows)
		{
			t += f.applyAsDouble(s);
		}
		return t;
	}
}
