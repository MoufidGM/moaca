package com.cslsm.web.finance;

import java.util.List;
import java.util.Locale;

/**
 * One or two series of amounts as vertical bars (a day or a month per slot), rendered on the
 * server as SVG like the monthly chart. Used by the Income page (this period, and the
 * previous one for comparison) and the year-over-year report. A null value draws no bar,
 * which is how a day without a log file shows up.
 */
public final class BarChartSvg
{
	/** css is the fill class: bar-income, bar-expense, bar-prev… */
	public record Series(String name, String css, List<Double> values)
	{
	}

	private static final int WIDTH = 760;
	private static final int HEIGHT = 280;
	private static final int LEFT = 60;
	private static final int RIGHT = 12;
	private static final int TOP = 34;
	private static final int BOTTOM = 32;
	/** Above this many slots, only every n-th label is written so they stay readable. */
	private static final int MAX_LABELS = 16;

	private BarChartSvg()
	{
	}

	/**
	 * @param labels   one short label per slot (generated, e.g. "12" or "Sep")
	 * @param titles   one tooltip prefix per slot (generated, e.g. "Tue 12 Sep")
	 * @param main     the series drawn in front
	 * @param compare  optional second series drawn beside it, or null
	 */
	public static String render(String ariaLabel, List<String> labels, List<String> titles, Series main, Series compare)
	{
		int n = labels.size();
		if (n == 0)
		{
			return "";
		}
		double max = 0;
		for (Series s : compare == null ? List.of(main) : List.of(main, compare))
		{
			for (Double v : s.values())
			{
				if (v != null)
				{
					max = Math.max(max, v);
				}
			}
		}
		if (max == 0)
		{
			max = 1000;
		}
		double step = MonthlyChartSvg.niceStep(max / 4);
		double hi = Math.ceil(max / step) * step;
		double plotW = WIDTH - LEFT - RIGHT;
		double plotH = HEIGHT - TOP - BOTTOM;

		StringBuilder s = new StringBuilder(8192);
		s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"chart\" viewBox=\"0 0 ").append(WIDTH).append(' ').append(HEIGHT)
				.append("\" role=\"img\" aria-label=\"").append(escape(ariaLabel)).append("\">");

		int x = LEFT;
		s.append("<rect class=\"").append(main.css()).append("\" x=\"").append(x).append("\" y=\"8\" width=\"12\" height=\"12\" rx=\"2\"/>")
				.append("<text class=\"legend\" x=\"").append(x + 18).append("\" y=\"18\">").append(escape(main.name())).append("</text>");
		if (compare != null)
		{
			x += 40 + 7 * main.name().length();
			s.append("<rect class=\"").append(compare.css()).append("\" x=\"").append(x).append("\" y=\"8\" width=\"12\" height=\"12\" rx=\"2\"/>")
					.append("<text class=\"legend\" x=\"").append(x + 18).append("\" y=\"18\">").append(escape(compare.name())).append("</text>");
		}

		int ticks = (int) Math.round(hi / step);
		for (int i = 0; i <= ticks; i++)
		{
			double value = i * step;
			double y = TOP + (hi - value) / hi * plotH;
			s.append("<line class=\"").append(i == 0 ? "axis" : "gridline").append("\" x1=\"").append(LEFT)
					.append("\" x2=\"").append(WIDTH - RIGHT).append("\" y1=\"").append(f(y)).append("\" y2=\"").append(f(y)).append("\"/>");
			s.append("<text class=\"tick\" x=\"").append(LEFT - 8).append("\" y=\"").append(f(y + 4))
					.append("\" text-anchor=\"end\">").append(MonthlyChartSvg.compact(value)).append("</text>");
		}

		double slot = plotW / n;
		int series = compare == null ? 1 : 2;
		double barW = Math.max(2, Math.min(22, slot * 0.7 / series));
		double zeroY = TOP + plotH;
		int every = (int) Math.ceil(n / (double) MAX_LABELS);
		StringBuilder bands = new StringBuilder();
		for (int i = 0; i < n; i++)
		{
			double cx = LEFT + slot * i + slot / 2;
			double startX = cx - barW * series / 2;
			if (compare != null)
			{
				bar(s, startX, barW, compare.values().get(i), hi, plotH, zeroY, compare.css());
				startX += barW;
			}
			bar(s, startX, barW, main.values().get(i), hi, plotH, zeroY, main.css());
			if (i % every == 0)
			{
				s.append("<text class=\"month\" x=\"").append(f(cx)).append("\" y=\"").append(HEIGHT - BOTTOM + 16)
						.append("\" text-anchor=\"middle\">").append(escape(labels.get(i))).append("</text>");
			}
			String mainLine = main.name() + " " + amount(main.values().get(i));
			if (compare == null)
			{
				ChartTips.band(bands, LEFT + slot * i, TOP, slot, plotH, titles.get(i), mainLine);
			}
			else
			{
				ChartTips.band(bands, LEFT + slot * i, TOP, slot, plotH, titles.get(i), mainLine,
						compare.name() + " " + amount(compare.values().get(i)));
			}
		}
		s.append(bands);
		s.append("</svg>");
		return s.toString();
	}

	private static String amount(Double value)
	{
		return value == null ? "— (no figure)" : String.format(Locale.US, "%,.0f", value);
	}

	private static void bar(StringBuilder s, double x, double w, Double value, double hi, double plotH, double zeroY, String css)
	{
		if (value == null)
		{
			return;
		}
		double top = TOP + (hi - Math.max(0, value)) / hi * plotH;
		s.append("<rect class=\"").append(css).append("\" x=\"").append(f(x)).append("\" y=\"").append(f(top))
				.append("\" width=\"").append(f(w)).append("\" height=\"").append(f(zeroY - top)).append("\" rx=\"1.5\"/>");
	}

	static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static String f(double v)
	{
		return String.format(Locale.ROOT, "%.1f", v);
	}
}
