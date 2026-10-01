package com.cslsm.ui;

import com.cslsm.model.DailySummary;
import com.cslsm.repo.DailyRepo;
import com.cslsm.util.AppConfig;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.awt.*;
import java.io.File;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class PaymentsAnalysisView extends BorderPane {

	private final DailyRepo repo = new DailyRepo(AppConfig.getDbPath());
	private static final double CARD_FEE_RATE = 0.019; // 1.9%
	private final DecimalFormat INT = new DecimalFormat("#,##0");

	private final DatePicker dpFrom = new DatePicker(LocalDate.now().minusMonths(1));
	private final DatePicker dpTo   = new DatePicker(LocalDate.now());
	private final Button btnRefresh = new Button("Refresh");
	private final TableView<Row> table = new TableView<>();

	private final Label lblCash   = new Label("Cash: 0");
	private final Label lblCard   = new Label("Card: 0");
	private final Label lblCheque = new Label("Cheque: 0");
	private final Label lblFee    = new Label("Card Fees (1.9%): 0");
	private final Label lblNet    = new Label("Estimated Net to Bank: 0");

	public PaymentsAnalysisView() {
		// Header
		Label title = new Label("Payments Analysis (Daily)");
		title.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 20));

		HBox totals = new HBox(15, lblCash, lblCard, lblCheque, lblFee, lblNet);
		totals.setAlignment(Pos.CENTER_RIGHT);
		totals.setPadding(new Insets(0, 10, 0, 10));

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);

		HBox topRow1 = new HBox(10, title, spacer, totals);
		topRow1.setAlignment(Pos.CENTER_LEFT);
		topRow1.setPadding(new Insets(8, 10, 0, 10));

		HBox topRow2 = new HBox(10,
				new Label("From:"), dpFrom,
				new Label("To:"), dpTo,
				btnRefresh
		);
		topRow2.setAlignment(Pos.CENTER_LEFT);
		topRow2.setPadding(new Insets(6, 10, 8, 10));

		VBox top = new VBox(topRow1, topRow2);
		setTop(top);

		// Table
		table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY); // allow horizontal scroll
		table.setFixedCellSize(26);
		buildColumns();

		VBox wrap = new VBox(table);
		VBox.setVgrow(table, Priority.ALWAYS);
		setCenter(wrap);

		btnRefresh.setOnAction(e -> reload());
		reload();
	}

	private void buildColumns() {
		// Date (hyperlink)
		TableColumn<Row, String> cDate = new TableColumn<>("Date");
		cDate.setPrefWidth(170);
		cDate.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().label));
		cDate.setCellFactory(col -> new TableCell<>() {
			private final Hyperlink link = new Hyperlink();
			private final Label lab = new Label();
			@Override protected void updateItem(String v, boolean empty) {
				super.updateItem(v, empty);
				if (empty) { setGraphic(null); setText(null); return; }
				Row r = getTableView().getItems().get(getIndex());
				if (r.isTotal) {
					lab.setText("TOTAL");
					lab.setStyle("-fx-font-weight:700; -fx-font-size:14px;");
					setGraphic(lab);
				} else {
					link.setText(v);
					link.setOnAction(e -> openFile(r.filePath));
					setGraphic(link);
				}
			}
		});

		TableColumn<Row, Number> cCash   = numCol("Cash",   r -> r.cash,   110);
		TableColumn<Row, Number> cCard   = numCol("Card",   r -> r.card,   110);
		TableColumn<Row, Number> cCheque = numCol("Cheque", r -> r.cheque, 110);
		TableColumn<Row, Number> cFee    = numCol("Card Fee (1.9%)", r -> r.cardFee, 140);
		TableColumn<Row, Number> cNet    = numCol("Estimated Net to Bank", r -> r.netToBank, 180);

		table.getColumns().setAll(cDate, cCash, cCard, cCheque, cFee, cNet);
	}

	private TableColumn<Row, Number> numCol(String title, java.util.function.ToDoubleFunction<Row> f, double prefW) {
		TableColumn<Row, Number> c = new TableColumn<>(title);
		c.setPrefWidth(prefW);
		c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.applyAsDouble(cd.getValue())));
		c.setCellFactory(col -> new TableCell<>() {
			@Override protected void updateItem(Number v, boolean empty) {
				super.updateItem(v, empty);
				if (empty) { setText(""); setStyle(""); return; }
				Row r = getTableView().getItems().get(getIndex());
				setText(INT.format(Math.round(v.doubleValue())));
				if (r.isTotal) setStyle("-fx-font-weight:700; -fx-font-size:14px;");
				else setStyle("");
			}
		});
		return c;
	}

	private void reload() {
		LocalDate start = dpFrom.getValue();
		LocalDate end   = dpTo.getValue();
		if (start == null || end == null || end.isBefore(start)) return;

		List<Row> rows = new ArrayList<>();
		LocalDate d = start;
		while (!d.isAfter(end)) {
			rows.add(toRow(d));
			d = d.plusDays(1);
		}

		// Totals row
		Row T = new Row();
		T.isTotal = true;
		for (Row r : rows) {
			T.cash      += r.cash;
			T.card      += r.card;
			T.cheque    += r.cheque;
			T.cardFee   += r.cardFee;
			T.netToBank += r.netToBank;
		}
		rows.add(T);

		table.getItems().setAll(rows);

		lblCash.setText("Cash: " + INT.format(Math.round(T.cash)));
		lblCard.setText("Card: " + INT.format(Math.round(T.card)));
		lblCheque.setText("Cheque: " + INT.format(Math.round(T.cheque)));
		lblFee.setText("Card Fees (1.9%): " + INT.format(Math.round(T.cardFee)));
		lblNet.setText("Estimated Net to Bank: " + INT.format(Math.round(T.netToBank)));
	}

	private Row toRow(LocalDate d) {
		Row r = new Row();
		r.label = d.toString(); // e.g. 2025-10-27
		try {
			var opt = repo.summaryFor(d);
			if (opt.isPresent()) {
				DailySummary s = opt.get();
				r.cash   = nz(s.getTotalCash());
				r.card   = nz(s.getTotalCard());
				r.cheque = nz(s.getTotalCheque());
				r.cardFee = r.card * CARD_FEE_RATE;
				r.netToBank = r.card - r.cardFee;
				r.filePath = s.getFilePath();
			}
		} catch (Exception ignored) { }
		return r;
	}

	private void openFile(String path) {
		try {
			if (path == null || path.isBlank()) return;
			var f = new File(path);
			if (f.exists()) Desktop.getDesktop().open(f);
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	private double nz(Double d) { return d == null ? 0d : d; }

	/** Row model **/
	static class Row {
		String label;
		double cash, card, cheque, cardFee, netToBank;
		String filePath;
		boolean isTotal = false;
	}
}
