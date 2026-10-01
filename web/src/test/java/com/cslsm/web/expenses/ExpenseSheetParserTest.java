package com.cslsm.web.expenses;

import com.cslsm.web.TestWorkbooks;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpenseSheetParserTest
{
	@Test
	void readsRowsAndSkipsTheHeader() throws Exception
	{
		byte[] sheet = TestWorkbooks.expenseSheet(
				new Object[]{"01-06-2026", "Supplies", "Ligat Ménage", 20, "Cash", "Marwane", "Siham", "Menage", "yes"},
				new Object[]{"2026-06-02", "Elictricity", "Facture", "1 250,50", null, null, null, "Terrains foot", "NO"});
		List<ExpenseSheetParser.SheetRow> rows = ExpenseSheetParser.parse(new ByteArrayInputStream(sheet));
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).rowNumber).isEqualTo(2);
		assertThat(rows.get(0).date).isEqualTo(LocalDate.of(2026, 6, 1));
		assertThat(rows.get(0).amount).isEqualTo(20.0);
		assertThat(rows.get(1).date).isEqualTo(LocalDate.of(2026, 6, 2));
		assertThat(rows.get(1).amount).isEqualTo(1250.5);
		assertThat(rows.get(1).payment).isNull();
	}

	@Test
	void mapsTheSpellingsSeenInRealData()
	{
		assertThat(ExpenseSheetParser.activityAlias("Ménage")).isEqualTo("Cleaning");
		assertThat(ExpenseSheetParser.activityAlias("T-Foot")).isEqualTo("Location de terrains");
		assertThat(ExpenseSheetParser.activityAlias("Terrains foot")).isEqualTo("Location de terrains");
		assertThat(ExpenseSheetParser.activityAlias("Academie")).isEqualTo("Académie");
		assertThat(ExpenseSheetParser.activityAlias("Gala-Match")).isEqualTo("Events");
		assertThat(ExpenseSheetParser.activityAlias("G3K")).isNull();
		assertThat(ExpenseSheetParser.categoryAlias("Elictricity")).isEqualTo("Electricity");
		assertThat(ExpenseSheetParser.categoryAlias("Avance  sur Salaire")).isEqualTo("Salary advance");
	}
}
