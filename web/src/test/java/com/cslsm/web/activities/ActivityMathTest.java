package com.cslsm.web.activities;

import com.cslsm.web.activities.ActivityMath.Result;
import com.cslsm.web.activities.ActivityMath.Row;
import com.cslsm.web.activities.ActivityMath.Spread;
import com.cslsm.web.activities.ActivityRules.Def;
import com.cslsm.web.activities.ActivityRules.Kind;
import com.cslsm.web.activities.ActivityRules.Rule;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ActivityMathTest
{
	private static final ActivityRules RULES = new ActivityRules(List.of(
			new Def(1, "Terrain", Rule.REVENUE, "total_terrain", null, true),
			new Def(2, "Gym", Rule.REVENUE, "total_gym", null, true),
			new Def(3, "General", Rule.SHARED, null, null, true),
			new Def(4, "Salon", Rule.SEPARATE, null, null, true),
			new Def(5, "Events", Rule.PART_OF, null, "Terrain", true),
			new Def(6, "Hack", Rule.REVENUE, "password_hash", null, true)),
			Set.of("total_terrain", "total_gym")::contains);

	private static Result compute(Spread spread)
	{
		LinkedHashMap<String, Double> revenue = new LinkedHashMap<>();
		revenue.put("Terrain", 30_000.0);
		revenue.put("Gym", 10_000.0);
		ActivityMath.Costs costs = new ActivityMath.Costs();
		costs.add(RULES.resolve("Terrain"), 2_000, 2);
		costs.add(RULES.resolve("events"), 500, 1);       // part of Terrain, any case
		costs.add(RULES.resolve("Gym"), 1_000, 1);
		costs.add(RULES.resolve("General"), 4_000, 3);     // shared
		costs.add(RULES.resolve("Salon"), 700, 1);          // own line
		costs.add(RULES.resolve("T-Foot"), 300, 1);         // not in the list
		return ActivityMath.compute(revenue, costs, spread);
	}

	@Test
	void rulesResolveNamesAndRejectUnknownColumns()
	{
		assertThat(RULES.revenueActivities()).extracting(d -> d.name).containsExactly("Terrain", "Gym");
		assertThat(RULES.resolve("events").kind).isEqualTo(Kind.REVENUE);
		assertThat(RULES.resolve("events").name).isEqualTo("Terrain");
		assertThat(RULES.resolve("GENERAL").kind).isEqualTo(Kind.SHARED);
		assertThat(RULES.resolve("T-Foot").kind).isEqualTo(Kind.UNASSIGNED);
		assertThat(RULES.resolve("Hack").kind).isEqualTo(Kind.SEPARATE); // bad column never reaches SQL
		assertThat(RULES.namesCountingAs("Terrain")).containsExactlyInAnyOrder("terrain", "events");
	}

	@Test
	void sharedCostsFollowRevenueByDefault()
	{
		Result r = compute(Spread.REVENUE);
		Row terrain = r.row("Terrain");
		Row gym = r.row("Gym");
		assertThat(terrain.directCosts).isEqualTo(2_500);
		assertThat(terrain.sharedCosts).isCloseTo(3_000, within(1e-9)); // 4000 × 30000/40000
		assertThat(gym.sharedCosts).isCloseTo(1_000, within(1e-9));
		assertThat(terrain.getProfit()).isCloseTo(24_500, within(1e-9));
		assertThat(terrain.getMargin()).isCloseTo(24_500 / 30_000.0, within(1e-9));
		assertThat(r.row("Salon").getProfit()).isEqualTo(-700);
		assertThat(r.unassignedTotal).isEqualTo(300);
	}

	@Test
	void everySpreadAddsUpToTheCenterProfit()
	{
		double expected = 40_000 - (2_000 + 500 + 1_000 + 4_000 + 700 + 300);
		for (Spread s : Spread.values())
		{
			Result r = compute(s);
			double sum = r.revenueRows.stream().mapToDouble(Row::getProfit).sum()
					+ r.otherRows.stream().mapToDouble(Row::getProfit).sum();
			assertThat(sum).as(s.name()).isCloseTo(expected, within(1e-6));
			assertThat(r.getProfit()).isCloseTo(expected, within(1e-6));
		}
		assertThat(compute(Spread.EQUAL).row("Gym").sharedCosts).isCloseTo(2_000, within(1e-9));
		assertThat(compute(Spread.NONE).row(ActivityMath.SHARED_ROW).getCosts()).isEqualTo(4_000);
	}

	@Test
	void noRevenueStillAddsUp()
	{
		LinkedHashMap<String, Double> revenue = new LinkedHashMap<>();
		revenue.put("Terrain", 0.0);
		revenue.put("Gym", 0.0);
		ActivityMath.Costs costs = new ActivityMath.Costs();
		costs.add(RULES.resolve("General"), 100, 1);
		Result r = ActivityMath.compute(revenue, costs, Spread.REVENUE);
		assertThat(r.row("Terrain").sharedCosts).isCloseTo(50, within(1e-9));
		assertThat(r.getProfit()).isCloseTo(-100, within(1e-9));
		assertThat(r.row("Terrain").getMargin()).isNull();
	}

	@Test
	void chartEscapesActivityNames()
	{
		String svg = ActivityBarsSvg.render(List.of(new ActivityBarsSvg.Bar("<script>x</script>", 10, 5)));
		assertThat(svg).doesNotContain("<script>").contains("&lt;script&gt;");
	}
}
