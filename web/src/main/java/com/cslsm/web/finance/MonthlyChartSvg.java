package com.cslsm.web.finance;

import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * Income vs expenses per month (bars) with net profit (line), rendered on the server as SVG.
 * No JavaScript chart library: works under a strict Content-Security-Policy and with no
 * internet access on the server. All text in the SVG is generated here (numbers, month
 * names), never taken from user input.
 *
 * Each month gets an invisible band over its whole column carrying a data-tip; app.js shows
 * it as a tooltip on hover or touch (see ChartTips).
 */
public final class MonthlyChartSvg
{
	public static final class Point
	{
		private final YearMonth month;
		private final double income;
		private final double expenses;

		public Point(YearMonth month, double income, double expenses)
		{
			this.month = month;
			this.income = income;
			this.expenses = expenses;
		}

		public YearMonth month()
		{
			return month;
		}

		public double income()
		{
			return income;
		}

		public double expenses()
		{
			return expenses;
		}

		public double net()
		{
			return income - expenses;
		}
	}

	private static final int WIDTH = 760;
	private static final int HEIGHT = 300;
	private static final int LEFT = 60;
	private static final int RIGHT = 12;
	private static final int TOP = 34;
	private static final int BOTTOM = 40;

	private MonthlyChartSvg()
	{
	}

	public static String render(List<Point> points)
	{
		if (points.isEmpty())
		{
			return "";
		}

		double max = 0;
		double min = 0;
		for (Point p : points)
		{
			max = Math.max(max, Math.max(p.income(), p.expenses()));
			max = Math.max(max, p.net());
			min = Math.min(min, p.net());
		}
		if (max == 0 && min == 0)
		{
			max = 1000; // nothing recorded yet: draw a sensible empty money axis
		}
		double step = niceStep((max - min) / 4);
		double lo = Math.floor(min / step) * step;
		double hi = Math.ceil(max / step) * step;
		if (hi <= lo)
		{
			hi = lo + step;
		}

		double plotW = WIDTH - LEFT - RIGHT;
		double plotH = HEIGHT - TOP - BOTTOM;
		StringBuilder s = new StringBuilder(8192);
		s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"chart\" viewBox=\"0 0 ")
				.append(WIDTH).append(' ').append(HEIGHT)
				.append("\" role=\"img\" aria-label=\"Income, expenses and net profit per month\">");

		legend(s);

		int ticks = (int) Math.round((hi - lo) / step);
		for (int i = 0; i <= ticks; i++)
		{
			double value = lo + i * step;
			double y = y(value, lo, hi, plotH);
			boolean zero = Math.abs(value) < step / 1000;
			s.append("<line class=\"").append(zero ? "axis" : "gridline").append("\" x1=\"").append(LEFT)
					.append("\" x2=\"").append(WIDTH - RIGHT).append("\" y1=\"").append(f(y))
					.append("\" y2=\"").append(f(y)).append("\"/>");
			s.append("<text class=\"tick\" x=\"").append(LEFT - 8).append("\" y=\"").append(f(y + 4))
					.append("\" text-anchor=\"end\">").append(compact(value)).append("</text>");
		}

		double slot = plotW / points.size();
		double barW = Math.max(4, Math.min(20, slot * 0.3));
		double zeroY = y(0, lo, hi, plotH);
		StringBuilder line = new StringBuilder();
		StringBuilder dots = new StringBuilder();
		StringBuilder bands = new StringBuilder();

		for (int i = 0; i < points.size(); i++)
		{
			Point p = points.get(i);
			double cx = LEFT + slot * i + slot / 2;
			String name = monthName(p.month()) + " " + p.month().getYear();

			bar(s, cx - barW - 1, barW, y(p.income(), lo, hi, plotH), zeroY, "bar-income");
			bar(s, cx + 1, barW, y(p.expenses(), lo, hi, plotH), zeroY, "bar-expense");

			double ny = y(p.net(), lo, hi, plotH);
			line.append(i == 0 ? "M" : " L").append(f(cx)).append(' ').append(f(ny));
			dots.append("<circle class=\"net-dot\" cx=\"").append(f(cx)).append("\" cy=\"").append(f(ny)).append("\" r=\"3.5\"/>");
			ChartTips.band(bands, LEFT + slot * i, TOP, slot, plotH,
					name, "Income " + money(p.income()), "Expenses " + money(p.expenses()), "Net profit " + money(p.net()));

			s.append("<text class=\"month\" x=\"").append(f(cx)).append("\" y=\"").append(HEIGHT - BOTTOM + 16)
					.append("\" text-anchor=\"middle\">").append(monthName(p.month())).append("</text>");
			if (i == 0 || p.month().getMonth() == Month.JANUARY)
			{
				s.append("<text class=\"year\" x=\"").append(f(cx)).append("\" y=\"").append(HEIGHT - BOTTOM + 30)
						.append("\" text-anchor=\"middle\">").append(p.month().getYear()).append("</text>");
			}
		}
		s.append("<path class=\"net-line\" d=\"").append(line).append("\"/>");
		s.append(dots);
		s.append(bands);
		s.append("</svg>");
		return s.toString();
	}

	private static void legend(StringBuilder s)
	{
		int x = LEFT;
		s.append("<rect class=\"bar-income\" x=\"").append(x).append("\" y=\"8\" width=\"12\" height=\"12\" rx=\"2\"/>")
				.append("<text class=\"legend\" x=\"").append(x + 18).append("\" y=\"18\">Income</text>");
		x += 90;
		s.append("<rect class=\"bar-expense\" x=\"").append(x).append("\" y=\"8\" width=\"12\" height=\"12\" rx=\"2\"/>")
				.append("<text class=\"legend\" x=\"").append(x + 18).append("\" y=\"18\">Expenses</text>");
		x += 100;
		s.append("<line class=\"net-line\" x1=\"").append(x).append("\" x2=\"").append(x + 16)
				.append("\" y1=\"14\" y2=\"14\"/>")
				.append("<text class=\"legend\" x=\"").append(x + 22).append("\" y=\"18\">Net profit</text>");
	}

	private static void bar(StringBuilder s, double x, double w, double valueY, double zeroY, String css)
	{
		double top = Math.min(valueY, zeroY);
		double height = Math.abs(zeroY - valueY);
		s.append("<rect class=\"").append(css).append("\" x=\"").append(f(x)).append("\" y=\"").append(f(top))
				.append("\" width=\"").append(f(w)).append("\" height=\"").append(f(height)).append("\" rx=\"2\"/>");
	}

	private static double y(double value, double lo, double hi, double plotH)
	{
		return TOP + (hi - value) / (hi - lo) * plotH;
	}

	/** 1, 2 or 5 times a power of ten — gives round axis labels. */
	static double niceStep(double raw)
	{
		if (raw <= 0 || Double.isNaN(raw))
		{
			return 1;
		}
		double base = Math.pow(10, Math.floor(Math.log10(raw)));
		double fraction = raw / base;
		double nice = fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10;
		return nice * base;
	}

	/** Axis labels: 250k, 1.5M, -50k. */
	static String compact(double value)
	{
		double abs = Math.abs(value);
		String text;
		if (abs >= 1_000_000)
		{
			text = trim(String.format(Locale.ROOT, "%.1f", value / 1_000_000)) + "M";
		}
		else if (abs >= 1_000)
		{
			text = trim(String.format(Locale.ROOT, "%.1f", value / 1_000)) + "k";
		}
		else
		{
			text = trim(String.format(Locale.ROOT, "%.1f", value));
		}
		return text;
	}

	private static String trim(String s)
	{
		return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
	}

	private static String money(double value)
	{
		return String.format(Locale.US, "%,.0f", value);
	}

	private static String monthName(YearMonth month)
	{
		return month.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
	}

	private static String f(double v)
	{
		return String.format(Locale.ROOT, "%.1f", v);
	}
}
