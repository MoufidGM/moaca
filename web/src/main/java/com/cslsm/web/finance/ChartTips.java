package com.cslsm.web.finance;

import java.util.Locale;

/**
 * Tooltips for the server-rendered charts. A chart lays an invisible band over each column
 * or row and writes the figures in its data-tip attribute (lines separated by "|"); app.js
 * turns that into a tooltip that follows the pointer, and highlights the band. The band
 * approach means the whole column is a target, not only the thin bar.
 */
public final class ChartTips
{
	private ChartTips()
	{
	}

	/** Appends a band; the first line is the heading, the others the figures. */
	public static void band(StringBuilder s, double x, double y, double width, double height, String... lines)
	{
		s.append("<rect class=\"hover-band\" x=\"").append(f(x)).append("\" y=\"").append(f(y))
				.append("\" width=\"").append(f(width)).append("\" height=\"").append(f(height))
				.append("\" data-tip=\"").append(escape(String.join("|", lines))).append("\"/>");
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
