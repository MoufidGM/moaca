package com.cslsm.web.dailylog;

import com.cslsm.web.TestWorkbooks;
import com.cslsm.web.dailylog.DailyLogParser.DailyLogException;
import com.cslsm.web.dailylog.DailyLogParser.ParsedDay;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DailyLogParserTest
{
	private static DailyLogParser parser() throws Exception
	{
		Properties p = new Properties();
		try (InputStream in = DailyLogParserTest.class.getResourceAsStream("/daily-log-layout.properties"))
		{
			p.load(in);
		}
		return new DailyLogParser(new DailyLogParser.Layout(p));
	}

	@Test
	void readsTheTemplateCells() throws Exception
	{
		byte[] file = TestWorkbooks.dailyLog(9440, 9550, 18990, 12000, 5090, 1900, 328, 59);
		ParsedDay d = parser().parse(new ByteArrayInputStream(file), LocalDate.of(2026, 9, 28));
		assertThat(d.terrain).isEqualTo(9440);
		assertThat(d.gym).isEqualTo(9550);
		assertThat(d.totalTtc).isEqualTo(18990);
		assertThat(d.cash).isEqualTo(12000);
		assertThat(d.card).isEqualTo(5090);
		assertThat(d.cheque).isEqualTo(1900);
		assertThat(d.departmentsTotal()).isEqualTo(18990);
		assertThat(d.paymentsTotal()).isEqualTo(18990);
	}

	@Test
	void findsDrinksByLabelWhetherTheTemplateHasItOnRow58Or59() throws Exception
	{
		DailyLogParser parser = parser();
		ParsedDay oldTemplate = parser.parse(new ByteArrayInputStream(TestWorkbooks.dailyLog(100, 0, 100, 100, 0, 0, 152, 58)), LocalDate.of(2026, 1, 15));
		ParsedDay newTemplate = parser.parse(new ByteArrayInputStream(TestWorkbooks.dailyLog(100, 0, 100, 100, 0, 0, 328, 59)), LocalDate.of(2026, 8, 15));
		assertThat(oldTemplate.drinks).isEqualTo(152);
		assertThat(newTemplate.drinks).isEqualTo(328);
	}

	@Test
	void fileNameRules() throws Exception
	{
		assertThat(DailyLogParser.dateFromFileName("DL-29-09-2026.xlsx")).isEqualTo(LocalDate.of(2026, 9, 29));
		assertThat(DailyLogParser.dateFromFileName("dl-01-08-2026 (1).xlsx")).isEqualTo(LocalDate.of(2026, 8, 1));
		assertThatThrownBy(() -> DailyLogParser.dateFromFileName("DL-14-08-2025.xsx.xlsx")).isInstanceOf(DailyLogException.class);
		assertThatThrownBy(() -> DailyLogParser.dateFromFileName("DL-31-02-2026.xlsx")).isInstanceOf(DailyLogException.class);
		assertThatThrownBy(() -> DailyLogParser.dateFromFileName("report.xlsx")).isInstanceOf(DailyLogException.class);
		assertThatThrownBy(() -> DailyLogParser.dateFromFileName(null)).isInstanceOf(DailyLogException.class);
	}

	@Test
	void rejectsWhatIsNotAWorkbook()
	{
		assertThatThrownBy(() -> parser().parse(new ByteArrayInputStream("not excel".getBytes()), LocalDate.of(2026, 9, 1)))
				.isInstanceOf(DailyLogException.class);
	}

	@Test
	void parsesMoneyTextLikeTheDesktopApp()
	{
		assertThat(DailyLogParser.parseMoney("1 250,50 dh")).isEqualTo(1250.5);
		assertThat(DailyLogParser.parseMoney("1,250.50")).isEqualTo(1250.5);
		assertThat(DailyLogParser.parseMoney("")).isEqualTo(0.0);
	}
}
