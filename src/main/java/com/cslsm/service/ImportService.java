package com.cslsm.service;

import com.cslsm.model.DailySummary;
import com.cslsm.repo.DailyRepo;
import com.cslsm.util.AppConfig;
import javafx.application.Platform;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class ImportService
{

	// Filename date pattern: DL-07-10-2025.xlsx -> dd-MM-yyyy
	private static final Pattern DATE_IN_NAME = Pattern.compile("(?i)DL-(\\d{2})-(\\d{2})-(\\d{4})\\.(xlsx|xls)$");
	// === Layout properties (all indices provided as 1-based in the file) ===
	private static final Properties LAYOUT = loadLayout();
	private final DailyRepo repo = new DailyRepo(AppConfig.getDbPath());
	private final ExecutorService watcherPool = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "cslsm-import-watcher");
		t.setDaemon(true);
		return t;
	});
	private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r ->
	{
		Thread t = new Thread(r, "cslsm-import-sweeper");
		t.setDaemon(true);
		return t;
	});
	private final List<ImportListener> listeners = new CopyOnWriteArrayList<>();
	private volatile boolean running = false;

	private static Properties loadLayout()
	{
		try (InputStream in = ImportService.class.getResourceAsStream("/cslsm-layout.properties"))
		{
			if (in == null) throw new FileNotFoundException("Missing /cslsm-layout.properties on classpath.");
			Properties p = new Properties();
			p.load(in);
			System.out.println("[CSLSM] Loaded layout from classpath: /cslsm-layout.properties");
			return p;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Cannot load cslsm-layout.properties: " + e.getMessage(), e);
		}
	}

	/* =================== PUBLIC API =================== */

	/**
	 * Read an integer from cslsm-layout.properties (1-based indices in file).
	 */
	private static int i(String key, int defVal)
	{
		String raw = LAYOUT.getProperty(key);
		if (raw == null || raw.isBlank())
		{
			System.out.println("[CSLSM] layout key missing, using default: " + key + " = " + defVal);
			return defVal;
		}
		try
		{
			return Integer.parseInt(raw.trim());
		}
		catch (NumberFormatException nfe)
		{
			System.out.println("[CSLSM] layout key not an integer, using default: " + key + "='" + raw + "' -> " + defVal);
			return defVal;
		}
	}

	/**
	 * Parse DL-dd-MM-yyyy from filename (e.g., DL-07-10-2025.xlsx).
	 */
	private static LocalDate parseDateFromFilename(String filename)
	{
		Matcher m = DATE_IN_NAME.matcher(filename);
		if (!m.find()) throw new IllegalArgumentException("Filename does not contain DL-dd-MM-yyyy date: " + filename);
		int dd = Integer.parseInt(m.group(1));
		int mm = Integer.parseInt(m.group(2));
		int yy = Integer.parseInt(m.group(3));
		return LocalDate.of(yy, mm, dd);
	}

	/**
	 * If desired path exists, produce 'name (n).ext' next free path.
	 */
	private static Path uniqueDestination(Path desired) throws IOException
	{
		if (!Files.exists(desired)) return desired;
		String name = desired.getFileName().toString();
		String base = name, ext = "";
		int i = name.lastIndexOf('.');
		if (i >= 0)
		{
			base = name.substring(0, i);
			ext = name.substring(i);
		}
		int n = 1;
		while (true)
		{
			Path trial = desired.getParent().resolve(base + " (" + n + ")" + ext);
			if (!Files.exists(trial)) return trial;
			n++;
		}
	}

	/* =================== WATCHER =================== */

	/**
	 * Read a double from (row0,col0) with debug prints like "Terrain [R,C]=value".
	 */
	private static double readNumber(Sheet sh, int row0, int col0, String label)
	{
		Row r = sh.getRow(row0);
		if (r == null)
		{
			System.out.printf(Locale.ENGLISH, "[CSLSM] %s [%d,%d]=<row-missing>%n", label, row0 + 1, col0 + 1);
			return 0.0;
		}
		Cell c = r.getCell(col0);
		if (c == null)
		{
			System.out.printf(Locale.ENGLISH, "[CSLSM] %s [%d,%d]=<cell-missing>%n", label, row0 + 1, col0 + 1);
			return 0.0;
		}
		try
		{
			double val;
			if (c.getCellType() == CellType.NUMERIC)
			{
				val = c.getNumericCellValue();
			}
			else if (c.getCellType() == CellType.FORMULA)
			{
				switch (c.getCachedFormulaResultType())
				{
					case NUMERIC -> val = c.getNumericCellValue();
					case STRING -> val = parseMoney(c.getStringCellValue());
					default -> val = 0.0;
				}
			}
			else if (c.getCellType() == CellType.STRING)
			{
				val = parseMoney(c.getStringCellValue());
			}
			else
			{
				val = 0.0;
			}
			System.out.printf(Locale.ENGLISH, "[CSLSM] %s [%d,%d]=%s%n", label, row0 + 1, col0 + 1, String.format(Locale.ENGLISH, "%,.0f", val));
			return val;
		}
		catch (Exception e)
		{
			System.out.printf(Locale.ENGLISH, "[CSLSM] %s [%d,%d]=<error %s>%n", label, row0 + 1, col0 + 1, e.getMessage());
			return 0.0;
		}
	}

	private static double parseMoney(String s)
	{
		if (s == null) return 0.0;
		String cleaned = s.replaceAll("[^0-9,.-]", "");
		if (cleaned.contains(",") && cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.'))
		{
			cleaned = cleaned.replace(".", "").replace(',', '.');
		}
		else
		{
			cleaned = cleaned.replace(",", "");
		}
		try
		{
			return Double.parseDouble(cleaned);
		}
		catch (Exception e)
		{
			return 0.0;
		}
	}

	public synchronized void startAutoImport()
	{
		if (running) return;
		running = true;

		watcherPool.submit(() ->
		{
			System.out.println("[CSLSM] Initial sweep of " + AppConfig.getUnprocessedDir());
			try
			{
				sweepOnce();
			}
			catch (Exception e)
			{
				System.err.println("[CSLSM] Initial sweep error: " + e);
			}
			watchLoop();
		});

		sweeper.scheduleWithFixedDelay(() ->
		{
			try
			{
				sweepOnce();
			}
			catch (Exception e)
			{
				System.err.println("[CSLSM] Sweep error: " + e);
			}
		}, 5, 5, TimeUnit.SECONDS);
	}

	/**
	 * Manually process a single file path (e.g., from 'Import Excel').
	 */
	public String processOne(Path sourceFile)
	{
		try
		{
			LocalDate date = parseDateFromFilename(sourceFile.getFileName().toString());
			Path dest = uniqueDestination(Paths.get(AppConfig.getProcessedDir()).resolve(sourceFile.getFileName()));

			Files.createDirectories(dest.getParent());
			Files.move(sourceFile, dest, StandardCopyOption.REPLACE_EXISTING);

			importExcel(dest, date); // throws on problems
			fireImported(date, dest);
			return "Imported " + dest.getFileName() + " for " + date + ".";
		}
		catch (Exception ex)
		{
			handleFailure(sourceFile, ex);
			fireFailed(sourceFile, ex.getMessage());
			return "Failed to import " + sourceFile.getFileName() + " (" + ex.getMessage() + ").";
		}
	}

	/* =================== CORE IMPORT =================== */

	/**
	 * Probe an Excel file WITHOUT writing to the DB.
	 * - Parses values using layout properties
	 * - Checks if that day already exists in DB
	 */
	public ProbeResult probe(Path excelFile)
	{
		ProbeResult pr = new ProbeResult();
		pr.sourceFile = excelFile;
		try
		{
			pr.date = parseDateFromFilename(excelFile.getFileName().toString());
			pr.parsed = parseExcel(excelFile, pr.date);
			pr.existing = repo.summaryFor(pr.date).orElse(null);
			pr.duplicate = (pr.existing != null);
			pr.ok = true;
			pr.message = pr.duplicate ? "Duplicate: day already imported" : "OK";
		}
		catch (Exception ex)
		{
			pr.ok = false;
			pr.message = ex.getMessage();
		}
		return pr;
	}

	private void watchLoop()
	{
		Path unprocessedPath = Paths.get(AppConfig.getUnprocessedDir());
		System.out.println("[CSLSM] Watching: " + unprocessedPath);
		try (WatchService ws = FileSystems.getDefault().newWatchService())
		{
			unprocessedPath.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);

			while (running)
			{
				WatchKey key = ws.take();
				for (WatchEvent<?> ev : key.pollEvents())
				{
					if (ev.kind() == StandardWatchEventKinds.OVERFLOW) continue;
					Path rel = (Path) ev.context();
					Path abs = unprocessedPath.resolve(rel);
					if (ev.kind() == StandardWatchEventKinds.ENTRY_DELETE) continue;
					if (shouldConsider(abs))
					{
						System.out.println("[CSLSM] FS event: " + ev.kind().name() + " " + abs);
						processIfStable(abs);
					}
				}
				key.reset();
			}
		}
		catch (InterruptedException ie)
		{
			Thread.currentThread().interrupt();
		}
		catch (Exception e)
		{
			System.err.println("[CSLSM] Watcher terminated: " + e);
		}
	}

	/* =================== HELPERS =================== */

	private boolean shouldConsider(Path p)
	{
		try
		{
			if (!Files.exists(p) || Files.isDirectory(p)) return false;
			String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
			if (name.startsWith(".") || name.startsWith("._") || name.equals(".ds_store")) return false;
			if (name.startsWith("~$") || name.endsWith(".tmp") || name.endsWith(".part")) return false;

			String extsProp = AppConfig.get("cslsm.excel.exts"); // ".xlsx,.xlsm,.xls" etc.
			String[] exts = (extsProp == null ? new String[]{".xlsx", ".xls"} : extsProp.split(","));
			for (String ext : exts)
			{
				if (name.endsWith(ext.trim().toLowerCase(Locale.ROOT))) return true;
			}
			return false;
		}
		catch (Exception e)
		{
			return false;
		}
	}

	private void processIfStable(Path p)
	{
		try
		{
			long last = -1;
			for (int i = 0; i < 6; i++)
			{
				long now = Files.size(p);
				if (now == last) break;
				last = now;
				Thread.sleep(500);
			}
			try (FileChannel ignored = FileChannel.open(p, StandardOpenOption.READ))
			{ /* ok */ }
			String msg = processOne(p);
			System.out.println("[CSLSM] " + msg);
		}
		catch (Exception e)
		{
			System.err.println("[CSLSM] processIfStable failed for " + p + ": " + e);
		}
	}

	private void sweepOnce()
	{
		Path unprocessed = Paths.get(AppConfig.getUnprocessedDir());
		try (Stream<Path> st = Files.list(unprocessed))
		{
			st.filter(this::shouldConsider).forEach(this::processIfStable);
		}
		catch (NoSuchFileException ignored)
		{
			// folder not present; fine
		}
		catch (Exception e)
		{
			System.err.println("[CSLSM] Sweep error: " + e);
		}
	}

	private void importExcel(Path pathInProcessedDir, LocalDate date) throws Exception
	{
		DailySummary s = parseExcel(pathInProcessedDir, date);
		repo.upsertSummary(s);
	}

	/**
	 * Parse an Excel file into a DailySummary using layout properties. No DB writes.
	 */
	private DailySummary parseExcel(Path path, LocalDate date) throws Exception
	{
		try (FileInputStream fis = new FileInputStream(path.toFile());
			 Workbook wb = new XSSFWorkbook(fis))
		{

			Sheet sh = wb.getSheetAt(0);

			int summaryHeaderRow1 = i("summary.headerRow", 9);
			int valueRowOffset = i("summary.valueRowOffset", 1);
			int summaryValueRow0 = (summaryHeaderRow1 - 1) + valueRowOffset;

			int COL_TERRAIN = i("summary.col.terrain", 1) - 1;
			int COL_PADEL = i("summary.col.padel", 4) - 1;
			int COL_GYM = i("summary.col.gym", 7) - 1;
			int COL_PARK = i("summary.col.park", 10) - 1;
			int COL_MINI_GOLF = i("summary.col.mini_golf", 13) - 1;
			int COL_PING_PONG = i("summary.col.ping_pong", 16) - 1;
			int COL_ACADEMY = i("summary.col.academy_foot", 19) - 1;
			int COL_TKD = i("summary.col.taekwondo", 22) - 1;
			int COL_SHOES = i("summary.col.shoes", 26) - 1;
			int COL_TOTAL_TTC = i("summary.col.total_ttc", 29) - 1;

			int DRINKS_ROW0 = i("drinks.amt.row", 58) - 1;
			int DRINKS_COL0 = i("drinks.amt.col", 31) - 1;

			int PAY_ROW0 = i("payments.row", 14) - 1;
			int PAY_CASH_COL0 = i("payments.col.cash", 1) - 1;
			int PAY_CARD_COL0 = i("payments.col.card", 13) - 1;
			int PAY_CHQ_COL0 = i("payments.col.cheque", 19) - 1;

			System.out.printf(Locale.ENGLISH, "[CSLSM] Layout: summaryValueRow=%d | terrain C=%d, padel=%d, gym=%d, park=%d, mini=%d, ping=%d, academy=%d, tkd=%d, shoes=%d, totalTtc=%d | drinks R=%d C=%d | payments R=%d cash=%d card=%d cheque=%d%n", summaryValueRow0, COL_TERRAIN, COL_PADEL, COL_GYM, COL_PARK, COL_MINI_GOLF, COL_PING_PONG, COL_ACADEMY, COL_TKD, COL_SHOES, COL_TOTAL_TTC, DRINKS_ROW0, DRINKS_COL0, PAY_ROW0, PAY_CASH_COL0, PAY_CARD_COL0, PAY_CHQ_COL0);

			double terrain = readNumber(sh, summaryValueRow0, COL_TERRAIN, "Terrain");
			double padel = readNumber(sh, summaryValueRow0, COL_PADEL, "Padel");
			double gym = readNumber(sh, summaryValueRow0, COL_GYM, "Gym");
			double park = readNumber(sh, summaryValueRow0, COL_PARK, "Park");
			double miniGolf = readNumber(sh, summaryValueRow0, COL_MINI_GOLF, "Mini Golf");
			double pingPong = readNumber(sh, summaryValueRow0, COL_PING_PONG, "Ping Pong");
			double academy = readNumber(sh, summaryValueRow0, COL_ACADEMY, "Academy");
			double tkd = readNumber(sh, summaryValueRow0, COL_TKD, "Taekwondo");
			double shoes = readNumber(sh, summaryValueRow0, COL_SHOES, "Shoes");
			double totalTtc = readNumber(sh, summaryValueRow0, COL_TOTAL_TTC, "Total TTC");

			double drinks = readNumber(sh, DRINKS_ROW0, DRINKS_COL0, "Drinks");

			double cash = readNumber(sh, PAY_ROW0, PAY_CASH_COL0, "Cash");
			double card = readNumber(sh, PAY_ROW0, PAY_CARD_COL0, "Card");
			double cheque = readNumber(sh, PAY_ROW0, PAY_CHQ_COL0, "Cheque");

			DailySummary s = new DailySummary();
			s.setLogDate(date.toString());
			s.setFilePath(path.toAbsolutePath().toString());

			s.setTotalTerrain(terrain);
			s.setTotalPadel(padel);
			s.setTotalGym(gym);
			s.setTotalPark(park);
			s.setTotalMiniGolf(miniGolf);
			s.setTotalPingPong(pingPong);
			s.setTotalAcademyFoot(academy);
			s.setTotalTaekwondo(tkd);
			s.setTotalShoes(shoes);
			s.setTotalTtc(totalTtc);
			s.setDrinksAmountTotal(drinks);

			s.setTotalCash(cash);
			s.setTotalCard(card);
			s.setTotalCheque(cheque);

			return s;
		}
	}

	private void handleFailure(Path originalFile, Exception ex)
	{
		Path processedDir = Paths.get(AppConfig.getProcessedDir());
		Path failedDir = Paths.get(AppConfig.getFailedDir());
		try
		{
			Path from = Files.exists(originalFile) ? originalFile : processedDir.resolve(originalFile.getFileName());
			Path failDest = uniqueDestination(failedDir.resolve(originalFile.getFileName()));
			if (Files.exists(from))
			{
				Files.move(from, failDest, StandardCopyOption.REPLACE_EXISTING);
			}
			appendFailureLog(failDest.getFileName().toString(), ex.toString());
		}
		catch (Exception moveEx)
		{
			appendFailureLog(originalFile.getFileName().toString(), "Secondary failure moving to FAILED: " + moveEx + " | primary: " + ex);
		}
	}

	private void appendFailureLog(String fileName, String reason)
	{
		Path failedDir = Paths.get(AppConfig.getFailedDir());
		String logName = "failed-" + DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDate.now()) + ".log";
		Path logPath = failedDir.resolve(logName);
		try (BufferedWriter bw = Files.newBufferedWriter(logPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND))
		{
			bw.write(java.time.LocalDateTime.now() + " | " + fileName + " | " + reason);
			bw.newLine();
		}
		catch (IOException ignored)
		{
		}
	}

	public void addListener(ImportListener l)
	{
		if (l != null) listeners.add(l);
	}

	public void removeListener(ImportListener l)
	{
		listeners.remove(l);
	}

	private void fireImported(LocalDate date, Path path)
	{
		Platform.runLater(() -> listeners.forEach(l -> l.onImported(date, path)));
	}

	private void fireFailed(Path file, String reason)
	{
		Platform.runLater(() -> listeners.forEach(l -> l.onFailed(file, reason)));
	}

	/**
	 * Event sink for finished imports.
	 */
	public interface ImportListener
	{
		void onImported(LocalDate date, Path finalFilePath);

		default void onFailed(Path file, String reason)
		{
		}
	}

	// === ProbeResult so UI can decide what to do before writing ===
	public static class ProbeResult
	{
		public boolean ok;
		public boolean duplicate;
		public String message;
		public LocalDate date;
		public Path sourceFile;
		public DailySummary parsed;   // values parsed from the Excel file
		public DailySummary existing; // existing DB row (if duplicate)
	}
}
