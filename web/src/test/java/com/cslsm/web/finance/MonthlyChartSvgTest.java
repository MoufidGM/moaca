package com.cslsm.web.finance;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MonthlyChartSvgTest
{
	@Test
	void rendersTwelveMonthsWithBarsLineAndLabels()
	{
		List<MonthlyChartSvg.Point> points = new ArrayList<>();
		for (int i = 0; i < 12; i++)
		{
			points.add(new MonthlyChartSvg.Point(YearMonth.of(2025, 10).plusMonths(i), 400_000 + i * 10_000, i * 20_000));
		}
		String svg = MonthlyChartSvg.render(points);
		assertThat(svg).startsWith("<svg").endsWith("</svg>");
		assertThat(svg).contains("net-line", "bar-income", "bar-expense", ">Oct<", ">2026<", "data-tip=\"Oct 2025|Income 400,000|Expenses 0|Net profit 400,000\"");
		assertThat(svg).doesNotContain("<title>");
		assertThat(svg).doesNotContain("height=\"-");
	}

	@Test
	void handlesLossesAndEmptyData()
	{
		String losses = MonthlyChartSvg.render(List.of(
				new MonthlyChartSvg.Point(YearMonth.of(2026, 1), 100_000, 180_000),
				new MonthlyChartSvg.Point(YearMonth.of(2026, 2), 100_000, 20_000)));
		assertThat(losses).contains(">-100k<").doesNotContain("height=\"-");

		String empty = MonthlyChartSvg.render(List.of(new MonthlyChartSvg.Point(YearMonth.of(2026, 1), 0, 0)));
		assertThat(empty).contains(">1k<").doesNotContain("NaN");

		assertThat(MonthlyChartSvg.render(List.of())).isEmpty();
	}

	@Test
	void axisLabelsAreCompact()
	{
		assertThat(MonthlyChartSvg.compact(600_000)).isEqualTo("600k");
		assertThat(MonthlyChartSvg.compact(1_500_000)).isEqualTo("1.5M");
		assertThat(MonthlyChartSvg.compact(-50_000)).isEqualTo("-50k");
		assertThat(MonthlyChartSvg.compact(0)).isEqualTo("0");
	}
}
