package com.cslsm.ui;

import com.cslsm.model.CashMovement;
import com.cslsm.model.Expense;
import com.cslsm.repo.CashRepo;
import com.cslsm.repo.ExpenseRepo;
import com.cslsm.util.AppConfig;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import org.apache.poi.ss.usermodel.Cell;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.*;

/**
 * Expenses tab: manual entry form + monthly table + Excel import.
 *
 * Excel import format (first sheet), one expense per row:
 *   Col A: Date (Excel date cell, or text dd-MM-yyyy / dd/MM/yyyy / yyyy-MM-dd)
 *   Col B: Category
 *   Col C: Description (optional)
 *   Col D: Amount
 *   Col E: Payment method (optional: CASH/CARD/CHEQUE/TRANSFER)
 *   Col F: Entered by (optional)
 *   Col G: Approved by (optional)
 *   Col H: Activity allocation (optional: Terrain, Padel, Gym, Academy, ... defaults to General)
 *   Col I: Paid from storage (optional: YES/NO, defaults to YES)
 * A header row is skipped automatically when col D is not numeric.
 */
public class ExpensesView extends BorderPane
{

	private final ExpenseRepo repo = new ExpenseRepo(AppConfig.getDbPath());
	private final CashRepo cashRepo = new CashRepo(AppConfig.getDbPath());

	// header
	private final Label title = new Label("EXPENSES");
	private final Button btnDeposit = new Button("Deposit…");
	private final Button btnWithdraw = new Button("Withdraw…");
	private final Button btnToBank = new Button("To bank…");
	private final Button btnSetBalance = new Button("Set balance…");
	private final Button btnMovements = new Button("Movements…");
	private final Button btnPrev = new Button("◀  Previous month");
	private final Button btnNext = new Button("Next month  ▶");
	private final ComboBox<Integer> cbYear = new ComboBox<>();
	private final ComboBox<Integer> cbMonth = new ComboBox<>();
	private final Label lblMonthTotal = new Label("TOTAL: 0");
	private final Label lblCount = new Label("");

	// entry form
	private final DatePicker fDate = new DatePicker(LocalDate.now());
	private final ComboBox<String> fCategory = new ComboBox<>();
	private final TextField fDescription = new TextField();
	private final TextField fAmount = new TextField();
	private final ComboBox<String> fPayment = new ComboBox<>();
	private final TextField fEnteredBy = new TextField();
	private final TextField fApprovedBy = new TextField();
	private final ComboBox<String> fActivity = new ComboBox<>();
	private final CheckBox fFromStorage = new CheckBox("Paid from storage");
	private final Button btnAdd = new Button("Add expense");
	private final Button btnImport = new Button("Import Excel…");

	// table + category breakdown
	private final TableView<Expense> table = new TableView<>();
	private final TableView<Map.Entry<String, Double>> catTable = new TableView<>();

	private final DecimalFormat INT = new DecimalFormat("#,##0");
	private final DecimalFormat MONEY = new DecimalFormat("#,##0.##");

	private boolean updatingCombos = false;

	public ExpensesView()
	{
		/* -------- Top: title row -------- */
		title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));
		lblMonthTotal.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 22));
		lblMonthTotal.setStyle("-fx-text-fill: #c0392b;");
		lblMonthTotal.setTooltip(new Tooltip("Total expenses of the selected month."));

		Region spacerL = new Region();
		Region spacerR = new Region();
		HBox.setHgrow(spacerL, Priority.ALWAYS);
		HBox.setHgrow(spacerR, Priority.ALWAYS);

		HBox titleRow = new HBox(10, spacerL, title, spacerR, lblCount, lblMonthTotal);
		titleRow.setAlignment(Pos.CENTER);
		titleRow.setPadding(new Insets(8, 10, 0, 10));

		/* -------- Top: month controls -------- */
		int nowYear = LocalDate.now().getYear();
		for (int y = nowYear - 5; y <= nowYear + 1; y++) cbYear.getItems().add(y);
		for (int m = 1; m <= 12; m++) cbMonth.getItems().add(m);
		cbYear.setValue(nowYear);
		cbMonth.setValue(LocalDate.now().getMonthValue());

		HBox ctrlRow = new HBox(12, btnPrev, new Label("Year:"), cbYear, new Label("Month:"), cbMonth, btnNext, btnImport, new Separator(javafx.geometry.Orientation.VERTICAL), btnDeposit, btnWithdraw, btnToBank, btnSetBalance, btnMovements);
		ctrlRow.setPadding(new Insets(4, 10, 4, 10));
		ctrlRow.setAlignment(Pos.CENTER_LEFT);

		/* -------- Top: entry form -------- */
		fCategory.getItems().addAll(Expense.DEFAULT_CATEGORIES);
		fCategory.setEditable(true);
		fCategory.setValue("Salaries");
		fCategory.setPrefWidth(150);

		fPayment.getItems().addAll(Expense.PAYMENT_METHODS);
		fPayment.setValue("CASH");

		fDescription.setPromptText("Description (optional)");
		fDescription.setPrefWidth(220);
		fAmount.setPromptText("Amount");
		fAmount.setPrefWidth(100);

		fActivity.getItems().addAll(Expense.ACTIVITIES);
		fActivity.setValue("General");
		fActivity.setPrefWidth(130);
		fEnteredBy.setPromptText("Entered by");
		fEnteredBy.setPrefWidth(140);
		fApprovedBy.setPromptText("Approved by");
		fApprovedBy.setPrefWidth(140);

		btnAdd.setDefaultButton(true);

		HBox formRow = new HBox(8, new Label("Date:"), fDate, new Label("Category:"), fCategory, fDescription, fAmount, fPayment);
		formRow.setPadding(new Insets(4, 10, 2, 10));
		formRow.setAlignment(Pos.CENTER_LEFT);

		fFromStorage.setSelected(true);

		HBox formRow2 = new HBox(8, new Label("Activity:"), fActivity, fEnteredBy, fApprovedBy, fFromStorage, btnAdd);
		formRow2.setPadding(new Insets(2, 10, 8, 10));
		formRow2.setAlignment(Pos.CENTER_LEFT);

		setTop(new VBox(titleRow, ctrlRow, formRow, formRow2));

		/* -------- Center: expense table + category breakdown -------- */
		buildTable();
		buildCatTable();

		VBox.setVgrow(table, Priority.ALWAYS);
		VBox left = new VBox(6, table);
		HBox.setHgrow(left, Priority.ALWAYS);

		Label catTitle = new Label("By category");
		catTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
		VBox right = new VBox(6, catTitle, catTable);
		right.setPrefWidth(260);
		VBox.setVgrow(catTable, Priority.ALWAYS);

		HBox center = new HBox(10, left, right);
		center.setPadding(new Insets(6, 10, 10, 10));
		setCenter(center);

		/* -------- events -------- */
		cbYear.valueProperty().addListener((o, a, b) -> { if (!updatingCombos) reload(); });
		cbMonth.valueProperty().addListener((o, a, b) -> { if (!updatingCombos) reload(); });
		btnPrev.setOnAction(e -> shiftMonth(-1));
		btnNext.setOnAction(e -> shiftMonth(1));
		btnAdd.setOnAction(e -> onAdd());
		btnImport.setOnAction(e -> onImport());
		btnDeposit.setOnAction(e -> onMovement(CashMovement.DEPOSIT));
		btnWithdraw.setOnAction(e -> onMovement(CashMovement.WITHDRAWAL));
		btnToBank.setOnAction(e -> onMovement(CashMovement.BANK));
		btnSetBalance.setOnAction(e -> onSetBalance());
		btnMovements.setOnAction(e -> showMovements());

		reload();
	}

	/* ===================== UI building ===================== */

	private void buildTable()
	{
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		table.setStyle("-fx-font-size: 13px; -fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial;");
		table.setPlaceholder(new Label("No expenses for this month."));

		TableColumn<Expense, String> cDate = new TableColumn<>("Date");
		cDate.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().getExpenseDate()));
		cDate.setPrefWidth(100);

		TableColumn<Expense, String> cCat = new TableColumn<>("Category");
		cCat.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().getCategory()));
		cCat.setPrefWidth(130);

		TableColumn<Expense, String> cDesc = new TableColumn<>("Description");
		cDesc.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().getDescription()));
		cDesc.setPrefWidth(240);

		TableColumn<Expense, String> cAmount = new TableColumn<>("Amount");
		cAmount.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().getAmount() == null ? "" : MONEY.format(cd.getValue().getAmount())));
		cAmount.setStyle("-fx-alignment: CENTER-RIGHT;");
		cAmount.setPrefWidth(100);

		TableColumn<Expense, String> cPay = new TableColumn<>("Payment");
		cPay.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().getPaymentMethod()));
		cPay.setPrefWidth(90);

		TableColumn<Expense, String> cActivity = new TableColumn<>("Activity");
		cActivity.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().getActivity()));
		cActivity.setPrefWidth(100);

		TableColumn<Expense, String> cEntered = new TableColumn<>("Entered by");
		cEntered.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().getEnteredBy()));
		cEntered.setPrefWidth(110);

		TableColumn<Expense, String> cApproved = new TableColumn<>("Approved by");
		cApproved.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().getApprovedBy()));
		cApproved.setPrefWidth(110);

		TableColumn<Expense, String> cStorage = new TableColumn<>("Storage");
		cStorage.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().isPaidFromStorage() ? "✓" : ""));
		cStorage.setStyle("-fx-alignment: CENTER;");
		cStorage.setPrefWidth(60);

		TableColumn<Expense, Void> cDel = new TableColumn<>("");
		cDel.setCellFactory(col -> new TableCell<>()
		{
			private final Button del = new Button("✕");

			{
				del.setStyle("-fx-text-fill: #c0392b; -fx-font-weight: bold;");
				del.setOnAction(e ->
				{
					Expense ex = getTableView().getItems().get(getIndex());
					onDelete(ex);
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

		table.getColumns().setAll(java.util.Arrays.asList(cDate, cCat, cDesc, cAmount, cPay, cActivity, cEntered, cApproved, cStorage, cDel));
	}

	private void buildCatTable()
	{
		catTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		catTable.setStyle("-fx-font-size: 13px;");
		catTable.setPlaceholder(new Label("—"));

		TableColumn<Map.Entry<String, Double>, String> cCat = new TableColumn<>("Category");
		cCat.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().getKey()));

		TableColumn<Map.Entry<String, Double>, String> cTot = new TableColumn<>("Total");
		cTot.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(INT.format(Math.round(cd.getValue().getValue()))));
		cTot.setStyle("-fx-alignment: CENTER-RIGHT;");

		catTable.getColumns().setAll(java.util.Arrays.asList(cCat, cTot));
	}

	/* ===================== Actions ===================== */

	private void shiftMonth(int delta)
	{
		YearMonth ym = YearMonth.of(cbYear.getValue(), cbMonth.getValue()).plusMonths(delta);
		updatingCombos = true;
		if (!cbYear.getItems().contains(ym.getYear())) cbYear.getItems().add(ym.getYear());
		cbYear.setValue(ym.getYear());
		cbMonth.setValue(ym.getMonthValue());
		updatingCombos = false;
		reload();
	}

	private void onAdd()
	{
		LocalDate date = fDate.getValue();
		String category = fCategory.getEditor().getText();
		if (category == null || category.isBlank()) category = fCategory.getValue();
		String amountRaw = fAmount.getText();

		if (date == null || category == null || category.isBlank() || amountRaw == null || amountRaw.isBlank())
		{
			warn("Date, category and amount are required.");
			return;
		}
		double amount;
		try
		{
			amount = Double.parseDouble(amountRaw.trim().replace(",", "."));
		}
		catch (NumberFormatException nfe)
		{
			warn("Amount is not a valid number: " + amountRaw);
			return;
		}

		Expense e = new Expense();
		e.setExpenseDate(date.toString());
		e.setCategory(category.trim());
		e.setDescription(fDescription.getText() == null ? null : fDescription.getText().trim());
		e.setAmount(amount);
		e.setPaymentMethod(fPayment.getValue());
		e.setEnteredBy(fEnteredBy.getText() == null ? null : fEnteredBy.getText().trim());
		e.setApprovedBy(fApprovedBy.getText() == null ? null : fApprovedBy.getText().trim());
		e.setActivity(fActivity.getValue());
		e.setPaidFromStorage(fFromStorage.isSelected());

		try
		{
			repo.insert(e);
		}
		catch (SQLException ex)
		{
			ex.printStackTrace();
			warn("Could not save expense: " + ex.getMessage());
			return;
		}

		fDescription.clear();
		fAmount.clear();

		// jump the view to the month of the new expense
		updatingCombos = true;
		if (!cbYear.getItems().contains(date.getYear())) cbYear.getItems().add(date.getYear());
		cbYear.setValue(date.getYear());
		cbMonth.setValue(date.getMonthValue());
		updatingCombos = false;
		reload();
	}

	private void onDelete(Expense e)
	{
		Alert a = new Alert(Alert.AlertType.CONFIRMATION,
				"Delete this expense?\n\n" + e.getExpenseDate() + "  " + e.getCategory() + "  " + MONEY.format(e.getAmount() == null ? 0 : e.getAmount()),
				ButtonType.YES, ButtonType.NO);
		a.setHeaderText(null);
		a.showAndWait().ifPresent(bt ->
		{
			if (bt == ButtonType.YES)
			{
				try
				{
					repo.delete(e.getId());
					reload();
				}
				catch (SQLException ex)
				{
					ex.printStackTrace();
					warn("Could not delete: " + ex.getMessage());
				}
			}
		});
	}

	private void onImport()
	{
		FileChooser fc = new FileChooser();
		fc.setTitle("Import expenses from Excel");
		fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel files", "*.xlsx", "*.xlsm"));
		File file = fc.showOpenDialog(getScene() == null ? null : getScene().getWindow());
		if (file == null) return;

		List<Expense> parsed = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		try (FileInputStream in = new FileInputStream(file);
			 Workbook wb = new XSSFWorkbook(in))
		{
			Sheet sheet = wb.getSheetAt(0);
			for (Row row : sheet)
			{
				if (row == null) continue;
				Expense e = parseRow(row, errors);
				if (e != null) parsed.add(e);
			}
		}
		catch (Exception ex)
		{
			ex.printStackTrace();
			warn("Could not read file: " + ex.getMessage());
			return;
		}

		if (parsed.isEmpty())
		{
			warn("No valid expense rows found.\nExpected columns: Date | Category | Description | Amount | Payment."
					+ (errors.isEmpty() ? "" : "\n\nFirst problems:\n" + String.join("\n", errors.subList(0, Math.min(5, errors.size())))));
			return;
		}

		try
		{
			repo.bulkInsert(parsed);
		}
		catch (SQLException ex)
		{
			ex.printStackTrace();
			warn("Import failed: " + ex.getMessage());
			return;
		}

		String msg = "Imported " + parsed.size() + " expense(s) from " + file.getName() + ".";
		if (!errors.isEmpty()) msg += "\nSkipped " + errors.size() + " row(s).";
		Alert ok = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
		ok.setHeaderText(null);
		ok.showAndWait();
		reload();
	}

	/** Parse one Excel row into an Expense, or null when the row should be skipped. */
	private Expense parseRow(Row row, List<String> errors)
	{
		LocalDate date = readDate(row.getCell(0));
		Double amount = readNumber(row.getCell(3));
		String category = readString(row.getCell(1));

		// Header or empty rows: skip silently when there is no date AND no amount
		if (date == null && amount == null) return null;

		int rowNum = row.getRowNum() + 1;
		if (date == null)
		{
			errors.add("Row " + rowNum + ": unreadable date.");
			return null;
		}
		if (amount == null)
		{
			errors.add("Row " + rowNum + ": unreadable amount.");
			return null;
		}
		if (category == null || category.isBlank())
		{
			errors.add("Row " + rowNum + ": missing category.");
			return null;
		}

		Expense e = new Expense();
		e.setExpenseDate(date.toString());
		e.setCategory(category.trim());
		e.setDescription(readString(row.getCell(2)));
		e.setAmount(amount);
		String pay = readString(row.getCell(4));
		e.setPaymentMethod(pay == null || pay.isBlank() ? null : pay.trim().toUpperCase(Locale.ROOT));
		String enteredBy = readString(row.getCell(5));
		e.setEnteredBy(enteredBy == null || enteredBy.isBlank() ? null : enteredBy.trim());
		String approvedBy = readString(row.getCell(6));
		e.setApprovedBy(approvedBy == null || approvedBy.isBlank() ? null : approvedBy.trim());
		String activity = readString(row.getCell(7));
		e.setActivity(activity == null || activity.isBlank() ? "General" : activity.trim());
		String fromStorage = readString(row.getCell(8));
		if (fromStorage != null && !fromStorage.isBlank())
		{
			String v = fromStorage.trim().toUpperCase(Locale.ROOT);
			e.setPaidFromStorage(!(v.startsWith("N") || v.equals("0") || v.equals("FALSE") || v.equals("0.0")));
		}
		return e;
	}

	private LocalDate readDate(Cell cell)
	{
		if (cell == null) return null;
		try
		{
			if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell))
			{
				return cell.getLocalDateTimeCellValue().toLocalDate();
			}
			String s = readString(cell);
			if (s == null || s.isBlank()) return null;
			s = s.trim();
			for (String pattern : new String[]{"yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy"})
			{
				try
				{
					return LocalDate.parse(s, DateTimeFormatter.ofPattern(pattern));
				}
				catch (Exception ignored)
				{
				}
			}
		}
		catch (Exception ignored)
		{
		}
		return null;
	}

	private Double readNumber(Cell cell)
	{
		if (cell == null) return null;
		try
		{
			if (cell.getCellType() == CellType.NUMERIC) return cell.getNumericCellValue();
			if (cell.getCellType() == CellType.FORMULA) return cell.getNumericCellValue();
			String s = readString(cell);
			if (s == null || s.isBlank()) return null;
			return Double.parseDouble(s.trim().replace(",", "."));
		}
		catch (Exception ignored)
		{
			return null;
		}
	}

	private String readString(Cell cell)
	{
		if (cell == null) return null;
		try
		{
			return switch (cell.getCellType())
			{
				case STRING -> cell.getStringCellValue();
				case NUMERIC -> String.valueOf(cell.getNumericCellValue());
				case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
				case FORMULA -> cell.getRichStringCellValue().getString();
				default -> null;
			};
		}
		catch (Exception ignored)
		{
			return null;
		}
	}

	private void warn(String msg)
	{
		Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
		a.setHeaderText(null);
		a.showAndWait();
	}

	/* ===================== Cash storage ===================== */

	/** Small dialog to record a DEPOSIT into, WITHDRAWAL from, or BANK transfer out of the local storage. */
	private void onMovement(String type)
	{
		boolean deposit = CashMovement.DEPOSIT.equals(type);
		boolean bank = CashMovement.BANK.equals(type);
		double current = cashRepo.balance();

		Dialog<ButtonType> dlg = new Dialog<>();
		dlg.setTitle(deposit ? "Deposit into storage" : bank ? "Transfer to bank" : "Withdraw from storage");
		dlg.setHeaderText((deposit ? "Add money to the local storage." : bank ? "Move money from the storage to the bank." : "Take money out of the local storage.")
				+ "\nCurrent storage: " + INT.format(Math.round(current)));
		dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

		DatePicker mDate = new DatePicker(LocalDate.now());
		TextField mAmount = new TextField();
		mAmount.setPromptText("Amount");
		TextField mNote = new TextField();
		mNote.setPromptText("Note (optional)");

		Label lblRemaining = new Label("Will remain in storage: " + INT.format(Math.round(current)));
		mAmount.textProperty().addListener((o, a, b) ->
		{
			try
			{
				double amt = Double.parseDouble(b.trim().replace(",", "."));
				double after = deposit ? current + amt : current - amt;
				lblRemaining.setText("Will remain in storage: " + INT.format(Math.round(after)));
				lblRemaining.setStyle(after < 0 ? "-fx-text-fill: #c0392b; -fx-font-weight: bold;" : "");
			}
			catch (Exception ex)
			{
				lblRemaining.setText("Will remain in storage: " + INT.format(Math.round(current)));
				lblRemaining.setStyle("");
			}
		});

		GridPane grid = new GridPane();
		grid.setHgap(8);
		grid.setVgap(8);
		grid.setPadding(new Insets(10));
		grid.addRow(0, new Label("Date:"), mDate);
		grid.addRow(1, new Label("Amount:"), mAmount);
		grid.addRow(2, new Label("Note:"), mNote);
		grid.add(lblRemaining, 0, 3, 2, 1);
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

		if (!deposit && amount > current)
		{
			Alert a = new Alert(Alert.AlertType.CONFIRMATION,
					"This takes out more than the current storage balance (" + INT.format(Math.round(current)) + ").\nSave anyway?",
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
			cashRepo.insert(m);
		}
		catch (SQLException ex)
		{
			ex.printStackTrace();
			warn("Could not save movement: " + ex.getMessage());
			return;
		}
		reload();
	}

	/**
	 * Set the storage balance to the amount actually counted in the safe.
	 * Records a single adjusting DEPOSIT or WITHDRAWAL for the difference.
	 * Enter 0 to empty the storage.
	 */
	private void onSetBalance()
	{
		double current = cashRepo.balance();

		TextInputDialog dlg = new TextInputDialog();
		dlg.setTitle("Set storage balance");
		dlg.setHeaderText("Current balance: " + INT.format(Math.round(current))
				+ "\nEnter the amount actually in the storage (0 to empty it).\nThe difference is recorded as an adjustment.");
		dlg.setContentText("Actual amount:");

		String raw = dlg.showAndWait().orElse(null);
		if (raw == null || raw.isBlank()) return;

		double target;
		try
		{
			target = Double.parseDouble(raw.trim().replace(",", "."));
		}
		catch (NumberFormatException nfe)
		{
			warn("Not a valid number: " + raw);
			return;
		}
		if (target < 0)
		{
			warn("The storage balance cannot be set below zero.");
			return;
		}

		double diff = target - current;
		if (Math.abs(diff) < 0.005)
		{
			Alert a = new Alert(Alert.AlertType.INFORMATION, "Balance is already " + INT.format(Math.round(target)) + ".", ButtonType.OK);
			a.setHeaderText(null);
			a.showAndWait();
			return;
		}

		CashMovement m = new CashMovement();
		m.setMovementDate(LocalDate.now().toString());
		m.setType(diff > 0 ? CashMovement.DEPOSIT : CashMovement.WITHDRAWAL);
		m.setAmount(Math.abs(diff));
		m.setNote("Balance adjustment: set to " + MONEY.format(target));

		try
		{
			cashRepo.insert(m);
		}
		catch (SQLException ex)
		{
			ex.printStackTrace();
			warn("Could not save adjustment: " + ex.getMessage());
			return;
		}
		reload();
	}

	/** Shows the most recent storage movements (deposits/withdrawals). */
	private void showMovements()
	{
		List<CashMovement> items;
		try
		{
			items = cashRepo.listRecent(200);
		}
		catch (SQLException ex)
		{
			ex.printStackTrace();
			warn("Could not load movements: " + ex.getMessage());
			return;
		}

		TableView<CashMovement> tv = new TableView<>();
		tv.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		tv.setPlaceholder(new Label("No movements recorded yet."));

		TableColumn<CashMovement, String> mDate = new TableColumn<>("Date");
		mDate.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().getMovementDate()));

		TableColumn<CashMovement, String> mType = new TableColumn<>("Type");
		mType.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(CashMovement.BANK.equals(cd.getValue().getType()) ? "TO BANK" : cd.getValue().getType()));

		TableColumn<CashMovement, String> mAmount = new TableColumn<>("Amount");
		mAmount.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>((CashMovement.DEPOSIT.equals(cd.getValue().getType()) ? "+" : "-") + MONEY.format(cd.getValue().getAmount() == null ? 0 : cd.getValue().getAmount())));
		mAmount.setStyle("-fx-alignment: CENTER-RIGHT;");

		TableColumn<CashMovement, String> mNote = new TableColumn<>("Note");
		mNote.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().getNote()));

		TableColumn<CashMovement, Void> mDel = new TableColumn<>("");
		mDel.setCellFactory(col -> new TableCell<>()
		{
			private final Button del = new Button("✕");

			{
				del.setStyle("-fx-text-fill: #c0392b; -fx-font-weight: bold;");
				del.setOnAction(e ->
				{
					CashMovement m = getTableView().getItems().get(getIndex());
					try
					{
						cashRepo.delete(m.getId());
						getTableView().getItems().remove(m);
						StorageBar.refreshActive();
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
		mDel.setPrefWidth(40);

		tv.getColumns().setAll(java.util.Arrays.asList(mDate, mType, mAmount, mNote, mDel));
		tv.getItems().setAll(items);
		tv.setPrefSize(560, 420);

		Dialog<ButtonType> dlg = new Dialog<>();
		dlg.setTitle("Storage movements");
		dlg.setHeaderText("Deposits, withdrawals and bank transfers (expenses paid from storage are deducted automatically)."
				+ "\nBalance: " + INT.format(Math.round(cashRepo.balance()))
				+ "    Sent to bank (all time): " + INT.format(Math.round(cashRepo.totalToBank())));
		dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
		dlg.getDialogPane().setContent(tv);
		dlg.setResizable(true);
		dlg.showAndWait();
	}

	/* ===================== Data ===================== */

	private void reload()
	{
		Integer y = cbYear.getValue();
		Integer m = cbMonth.getValue();
		if (y == null || m == null) return;

		YearMonth ym = YearMonth.of(y, m);
		LocalDate start = ym.atDay(1);
		LocalDate end = ym.atEndOfMonth();

		title.setText("EXPENSES — " + ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toUpperCase(Locale.ENGLISH) + " " + y);

		List<Expense> items;
		try
		{
			items = repo.findBetween(start, end);
		}
		catch (SQLException e)
		{
			e.printStackTrace();
			items = List.of();
		}

		table.getItems().setAll(items);

		double total = items.stream().mapToDouble(e -> e.getAmount() == null ? 0 : e.getAmount()).sum();
		lblMonthTotal.setText("TOTAL: " + INT.format(Math.round(total)));
		lblCount.setText(items.size() + " entries");

		Map<String, Double> byCat = repo.sumByCategoryBetween(start, end);
		catTable.getItems().setAll(new ArrayList<>(byCat.entrySet()));

		StorageBar.refreshActive();

		// merge any custom categories into the combo suggestions
		for (String c : repo.distinctCategories())
		{
			if (!fCategory.getItems().contains(c)) fCategory.getItems().add(c);
		}
	}
}
