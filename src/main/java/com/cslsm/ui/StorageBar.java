package com.cslsm.ui;

import com.cslsm.model.CashMovement;
import com.cslsm.repo.CashRepo;
import com.cslsm.repo.SafeRepo;
import com.cslsm.repo.SettingsRepo;
import com.cslsm.util.AppConfig;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.sql.SQLException;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.List;

/**
 * Global bar shown at the very top of the app, on every view.
 *
 * STORAGE (reception): all income + deposits - withdrawals/bank - expenses paid from storage - transfers to safe.
 * SAFE (physical): fed manually from the storage every 10-15 days; money leaves it to the bank or for payments.
 *
 * Each amount has its own eye button to show/hide it (persisted).
 */
public class StorageBar extends HBox
{

	private static final String HIDDEN = "•••••";
	private static volatile StorageBar ACTIVE;

	private final CashRepo cashRepo = new CashRepo(AppConfig.getDbPath());
	private final SafeRepo safeRepo = new SafeRepo(AppConfig.getDbPath());
	private final SettingsRepo settings = new SettingsRepo(AppConfig.getDbPath());

	private final Label lblTitle = new Label("BALANCE:");
	private final Label lblValue = new Label(HIDDEN);
	private final ToggleButton btnEye = new ToggleButton("👁");

	private final Label lblSafeTitle = new Label("SAFE:");
	private final Label lblSafeValue = new Label(HIDDEN);
	private final ToggleButton btnSafeEye = new ToggleButton("👁");
	private final MenuButton btnSafe = new MenuButton("Safe…");

	private final ComboBox<String> cbMode = new ComboBox<>();
	private final Button btnRefresh = new Button("⟳");

	private final DecimalFormat INT = new DecimalFormat("#,##0");
	private final DecimalFormat MONEY = new DecimalFormat("#,##0.##");
	private boolean updatingMode = false;

	public StorageBar()
	{
		ACTIVE = this;

		lblTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));
		lblValue.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 18));
		lblSafeTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));
		lblSafeValue.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 18));

		btnEye.setTooltip(new Tooltip("Show / hide the balance amount"));
		btnEye.setSelected("1".equals(settings.get("storage.visible", "0")));
		btnSafeEye.setTooltip(new Tooltip("Show / hide the safe amount"));
		btnSafeEye.setSelected("1".equals(settings.get("safe.visible", "0")));

		lblTitle.setTooltip(new Tooltip("Money at the reception:\nincome + deposits − withdrawals/bank − expenses paid from it − transfers to the safe."));
		lblSafeTitle.setTooltip(new Tooltip("Physical cash in the safe:\ntransfers received from the balance − sent to bank − payments from the safe."));

		MenuItem miIn = new MenuItem("Transfer from balance…");
		MenuItem miBank = new MenuItem("Send to bank…");
		MenuItem miOut = new MenuItem("Pay from safe…");
		MenuItem miMoves = new MenuItem("Movements…");
		btnSafe.getItems().addAll(miIn, miBank, miOut, new SeparatorMenuItem(), miMoves);

		cbMode.getItems().addAll("Carry over", "Reset each year");
		cbMode.setValue(cashRepo.resetEachYear() ? "Reset each year" : "Carry over");
		cbMode.setTooltip(new Tooltip("Storage year mode.\nCarry over: the balance keeps accumulating across years.\nReset each year: the balance restarts from 0 every January 1st."));

		btnRefresh.setTooltip(new Tooltip("Recompute the balances"));

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);

		Separator sep = new Separator(javafx.geometry.Orientation.VERTICAL);

		getChildren().addAll(lblTitle, lblValue, btnEye, sep, lblSafeTitle, lblSafeValue, btnSafeEye, btnSafe,
				spacer, new Label("Year mode:"), cbMode, btnRefresh);
		setSpacing(10);
		setAlignment(Pos.CENTER_LEFT);
		setPadding(new Insets(6, 12, 6, 12));
		setStyle("-fx-background-color: -fx-control-inner-background-alt; -fx-border-color: -fx-box-border; -fx-border-width: 0 0 1 0;");

		btnEye.setOnAction(e ->
		{
			settings.set("storage.visible", btnEye.isSelected() ? "1" : "0");
			refresh();
		});
		btnSafeEye.setOnAction(e ->
		{
			settings.set("safe.visible", btnSafeEye.isSelected() ? "1" : "0");
			refresh();
		});
		cbMode.valueProperty().addListener((o, a, b) ->
		{
			if (updatingMode || b == null) return;
			settings.set("storage.year_mode", "Reset each year".equals(b) ? "RESET" : "CARRY");
			refresh();
		});
		btnRefresh.setOnAction(e -> refresh());

		miIn.setOnAction(e -> safeAction(SafeRepo.IN));
		miBank.setOnAction(e -> safeAction(SafeRepo.BANK));
		miOut.setOnAction(e -> safeAction(SafeRepo.OUT));
		miMoves.setOnAction(e -> showSafeMovements());

		refresh();
	}

	/** Refresh the currently shown bar from anywhere in the app (no-op when none exists). */
	public static void refreshActive()
	{
		StorageBar bar = ACTIVE;
		if (bar != null) bar.refresh();
	}

	public void refresh()
	{
		boolean reset = cashRepo.resetEachYear();
		updatingMode = true;
		cbMode.setValue(reset ? "Reset each year" : "Carry over");
		updatingMode = false;

		lblTitle.setText(reset ? "BALANCE (" + LocalDate.now().getYear() + "):" : "BALANCE:");

		if (btnEye.isSelected())
		{
			double balance = cashRepo.balance();
			lblValue.setText(INT.format(Math.round(balance)));
			lblValue.setStyle("-fx-text-fill: " + (balance < 0 ? "#c0392b" : "#1e6fb8") + ";");
		}
		else
		{
			lblValue.setText(HIDDEN);
			lblValue.setStyle("-fx-text-fill: -fx-text-base-color;");
		}

		if (btnSafeEye.isSelected())
		{
			double safe = safeRepo.balance();
			lblSafeValue.setText(INT.format(Math.round(safe)));
			lblSafeValue.setStyle("-fx-text-fill: " + (safe < 0 ? "#c0392b" : "#1e8e3e") + ";");
		}
		else
		{
			lblSafeValue.setText(HIDDEN);
			lblSafeValue.setStyle("-fx-text-fill: -fx-text-base-color;");
		}
	}

	/* ===================== Safe transactions ===================== */

	private void safeAction(String type)
	{
		boolean in = SafeRepo.IN.equals(type);
		boolean bank = SafeRepo.BANK.equals(type);
		double storage = cashRepo.balance();
		double safe = safeRepo.balance();

		Dialog<ButtonType> dlg = new Dialog<>();
		dlg.setTitle(in ? "Transfer from balance to safe" : bank ? "Send from safe to bank" : "Pay from safe");
		dlg.setHeaderText((in ? "Move money from the reception balance into the safe."
				: bank ? "Take money out of the safe and send it to the bank."
				: "Pay something directly out of the safe.")
				+ "\nBalance: " + INT.format(Math.round(storage)) + "    Safe: " + INT.format(Math.round(safe)));
		dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

		DatePicker mDate = new DatePicker(LocalDate.now());
		TextField mAmount = new TextField();
		mAmount.setPromptText("Amount");
		TextField mNote = new TextField();
		mNote.setPromptText("Note (optional)");

		Label lblAfter = new Label("");
		mAmount.textProperty().addListener((o, a, b) ->
		{
			try
			{
				double amt = Double.parseDouble(b.trim().replace(",", "."));
				double afterStorage = in ? storage - amt : storage;
				double afterSafe = in ? safe + amt : safe - amt;
				lblAfter.setText("After: balance " + INT.format(Math.round(afterStorage)) + ", safe " + INT.format(Math.round(afterSafe)));
				lblAfter.setStyle((afterStorage < 0 || afterSafe < 0) ? "-fx-text-fill: #c0392b; -fx-font-weight: bold;" : "");
			}
			catch (Exception ex)
			{
				lblAfter.setText("");
				lblAfter.setStyle("");
			}
		});

		GridPane grid = new GridPane();
		grid.setHgap(8);
		grid.setVgap(8);
		grid.setPadding(new Insets(10));
		grid.addRow(0, new Label("Date:"), mDate);
		grid.addRow(1, new Label("Amount:"), mAmount);
		grid.addRow(2, new Label("Note:"), mNote);
		grid.add(lblAfter, 0, 3, 2, 1);
		dlg.getDialogPane().setContent(grid);

		if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

		double amount;
		try
		{
			amount = Double.parseDouble(mAmount.getText().trim().replace(",", "."));
		}
		catch (Exception ex)
		{
			warn("Amount is not a valid number: " + mAmount.getText());
			return;
		}
		if (amount <= 0)
		{
			warn("Amount must be greater than zero.");
			return;
		}

		double afterSource = in ? storage - amount : safe - amount;
		if (afterSource < 0)
		{
			Alert a = new Alert(Alert.AlertType.CONFIRMATION,
					"This takes out more than the current " + (in ? "balance" : "safe") + " amount.\nSave anyway?",
					ButtonType.YES, ButtonType.NO);
			a.setHeaderText(null);
			if (a.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
		}

		CashMovement m = new CashMovement();
		m.setMovementDate((mDate.getValue() != null ? mDate.getValue() : LocalDate.now()).toString());
		m.setType(type);
		m.setAmount(amount);
		m.setNote(mNote.getText() == null ? null : mNote.getText().trim());

		try
		{
			safeRepo.insert(m);
		}
		catch (SQLException ex)
		{
			ex.printStackTrace();
			warn("Could not save safe movement: " + ex.getMessage());
			return;
		}
		refresh();
	}

	private void showSafeMovements()
	{
		List<CashMovement> items;
		try
		{
			items = safeRepo.listRecent(200);
		}
		catch (SQLException ex)
		{
			ex.printStackTrace();
			warn("Could not load safe movements: " + ex.getMessage());
			return;
		}

		TableView<CashMovement> tv = new TableView<>();
		tv.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		tv.setPlaceholder(new Label("No safe movements recorded yet."));

		TableColumn<CashMovement, String> cDate = new TableColumn<>("Date");
		cDate.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().getMovementDate()));

		TableColumn<CashMovement, String> cType = new TableColumn<>("Type");
		cType.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(
				SafeRepo.IN.equals(cd.getValue().getType()) ? "FROM BALANCE"
						: SafeRepo.BANK.equals(cd.getValue().getType()) ? "TO BANK" : "PAYMENT"));

		TableColumn<CashMovement, String> cAmount = new TableColumn<>("Amount");
		cAmount.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(
				(SafeRepo.IN.equals(cd.getValue().getType()) ? "+" : "-") + MONEY.format(cd.getValue().getAmount() == null ? 0 : cd.getValue().getAmount())));
		cAmount.setStyle("-fx-alignment: CENTER-RIGHT;");

		TableColumn<CashMovement, String> cNote = new TableColumn<>("Note");
		cNote.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().getNote()));

		TableColumn<CashMovement, Void> cDel = new TableColumn<>("");
		cDel.setCellFactory(col -> new TableCell<>()
		{
			private final Button del = new Button("✕");

			{
				del.setStyle("-fx-text-fill: #c0392b; -fx-font-weight: bold;");
				del.setOnAction(e ->
				{
					CashMovement m = getTableView().getItems().get(getIndex());
					try
					{
						safeRepo.delete(m.getId());
						getTableView().getItems().remove(m);
						refresh();
					}
					catch (SQLException ex)
					{
						ex.printStackTrace();
						warn("Could not delete: " + ex.getMessage());
					}
				});
			}

			@Override
			protected void updateItem(Void v, boolean empty)
			{
				super.updateItem(v, empty);
				setGraphic(empty ? null : del);
			}
		});
		cDel.setPrefWidth(40);

		tv.getColumns().setAll(java.util.Arrays.asList(cDate, cType, cAmount, cNote, cDel));
		tv.getItems().setAll(items);
		tv.setPrefSize(560, 420);

		Dialog<ButtonType> dlg = new Dialog<>();
		dlg.setTitle("Safe movements");
		dlg.setHeaderText("Safe balance: " + INT.format(Math.round(safeRepo.balance()))
				+ "    Sent to bank from safe (all time): " + INT.format(Math.round(safeRepo.totalToBank())));
		dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
		dlg.getDialogPane().setContent(tv);
		dlg.setResizable(true);
		dlg.showAndWait();
	}

	private void warn(String msg)
	{
		Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
		a.setHeaderText(null);
		a.showAndWait();
	}
}
