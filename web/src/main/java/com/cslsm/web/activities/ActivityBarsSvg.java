package com.cslsm.web.activities;

import com.cslsm.web.finance.ChartTips;

import java.util.List;
import java.util.Locale;

/**
 * Revenue vs costs per activity as horizontal bars, with the profit written at the end of
 * each row. Server-rendered SVG like the monthly chart (strict CSP, no chart library).
 * Activity names come from the admin-managed list, so they are XML-escaped.
 */
public final class ActivityBarsSvg
{
	public static final class Bar
	{
		final String name;
		final double revenue;
		final double costs;

		public Bar(String name, double revenue, double costs)
		{
			this.name = name;
			this.revenue = revenue;
			this.costs = costs;
		}
	}

	private static final int WIDTH = 760;
	private static final int LABEL = 120;
	private static final int PROFIT = 96;
	private static final int ROW = 34;
	private static final int TOP = 30;

	private ActivityBarsSvg()
	{
	}

	public static String render(List<Bar> bars)
	{
		if (bars.isEmpty())
		{
			return "";
		}
		double max = 1;
		for (Bar b : bars)
		{
			max = Math.max(max, Math.max(b.revenue, b.costs));
		}
		int height = TOP + bars.size() * ROW + 8;
		double plot = WIDTH - LABEL - PROFIT - 8;

		StringBuilder s = new StringBuilder(4096);
		s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"chart bars\" viewBox=\"0 0 ").append(WIDTH).append(' ').append(height)
				.append("\" role=\"img\" aria-label=\"Revenue and costs per activity\">");
		s.append("<rect class=\"bar-income\" x=\"").append(LABEL).append("\" y=\"8\" width=\"12\" height=\"12\" rx=\"2\"/>")
				.append("<text class=\"legend\" x=\"").append(LABEL + 18).append("\" y=\"18\">Revenue</text>")
				.append("<rect class=\"bar-expense\" x=\"").append(LABEL + 100).append("\" y=\"8\" width=\"12\" height=\"12\" rx=\"2\"/>")
				.append("<text class=\"legend\" x=\"").append(LABEL + 118).append("\" y=\"18\">Costs (incl. share of common costs)</text>")
				.append("<text class=\"legend\" x=\"").append(WIDTH - 4).append("\" y=\"18\" text-anchor=\"end\">Profit</text>");

		StringBuilder bands = new StringBuilder();
		for (int i = 0; i < bars.size(); i++)
		{
			Bar b = bars.get(i);
			int y = TOP + i * ROW;
			String name = escape(b.name);
			double profit = b.revenue - b.costs;
			s.append("<text class=\"bar-label\" x=\"").append(LABEL - 8).append("\" y=\"").append(y + 17)
					.append("\" text-anchor=\"end\">").append(name).append("</text>");
			s.append("<rect class=\"bar-income\" x=\"").append(LABEL).append("\" y=\"").append(y + 3)
					.append("\" width=\"").append(f(Math.max(0, b.revenue) / max * plot)).append("\" height=\"12\" rx=\"2\"/>");
			s.append("<rect class=\"bar-expense\" x=\"").append(LABEL).append("\" y=\"").append(y + 17)
					.append("\" width=\"").append(f(Math.max(0, b.costs) / max * plot)).append("\" height=\"12\" rx=\"2\"/>");
			s.append("<text class=\"bar-profit ").append(profit < 0 ? "negative" : "positive").append("\" x=\"").append(WIDTH - 4)
					.append("\" y=\"").append(y + 20).append("\" text-anchor=\"end\">").append(money(profit)).append("</text>");
			ChartTips.band(bands, 0, y, WIDTH, ROW, b.name, "Revenue " + money(b.revenue), "Costs " + money(b.costs),
					"Profit " + money(profit));
		}
		s.append(bands);
		s.append("</svg>");
		return s.toString();
	}

	static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static String money(double v)
	{
		return String.format(Locale.US, "%,.0f", v);
	}

	private static String f(double v)
	{
		return String.format(Locale.ROOT, "%.1f", v);
	}
}
