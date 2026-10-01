package com.cslsm.web.reports;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A report as a plain grid: a label column, numeric columns, and rows of a few kinds.
 * The page renders it and the exports write the very same cells, so the file always matches
 * the screen.
 */
public record ReportTable(List<String> columns, List<Row> rows)
{
	public enum Kind
	{
		NORMAL, SECTION, SUBTOTAL, TOTAL
	}

	/** value null = nothing to show (a month not started yet, a division by zero). */
	public record Cell(Double value, boolean percent)
	{
		public static final Cell EMPTY = new Cell(null, false);

		public static Cell money(Double value)
		{
			return new Cell(value, false);
		}

		public static Cell percent(Double value)
		{
			return new Cell(value, true);
		}

		public String display()
		{
			if (value == null)
			{
				return "—";
			}
			if (percent)
			{
				return String.format(Locale.US, "%.0f%%", value * 100);
			}
			double rounded = Math.abs(value) < 0.5 ? 0 : value; // never "-0"
			return String.format(Locale.US, "%,.0f", rounded);
		}
	}

	/** signed: colour negative amounts red and positive green (net profit rows). */
	public record Row(String label, List<Cell> cells, Kind kind, boolean signed)
	{
		public static Row section(String label)
		{
			return new Row(label, List.of(), Kind.SECTION, false);
		}

		public static Row of(String label, List<Double> values)
		{
			return new Row(label, values.stream().map(Cell::money).toList(), Kind.NORMAL, false);
		}

		public boolean isSection()
		{
			return kind == Kind.SECTION;
		}

		public String css()
		{
			return switch (kind)
			{
				case SECTION -> "group-head";
				case SUBTOTAL -> "row-subtotal";
				case TOTAL -> "row-total";
				default -> "";
			};
		}

		/** CSS class for one cell: positive/negative on signed rows. */
		public String cellCss(Cell c)
		{
			if (!signed || c.value() == null || c.percent())
			{
				return "";
			}
			return c.value() < 0 ? "negative" : c.value() > 0 ? "positive" : "";
		}
	}

	/** Sum per column of the given value lists (null where every input is null). */
	static List<Double> sumColumns(List<List<Double>> rows, int columns)
	{
		List<Double> out = new ArrayList<>();
		for (int c = 0; c < columns; c++)
		{
			Double sum = null;
			for (List<Double> r : rows)
			{
				Double v = r.get(c);
				if (v != null)
				{
					sum = (sum == null ? 0 : sum) + v;
				}
			}
			out.add(sum);
		}
		return out;
	}
}
