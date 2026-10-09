package blocky_momot;

import java.lang.reflect.Field;

import org.moeaframework.algorithm.NSGAII;
import org.moeaframework.core.EpsilonBoxDominanceArchive;
import org.moeaframework.core.Initialization;
import org.moeaframework.core.NondominatedSortingPopulation;
import org.moeaframework.core.Population;
import org.moeaframework.core.Selection;
import org.moeaframework.core.Solution;
import org.moeaframework.core.Variation;

/**
 * NSGA-II with random immigrants (Exploration-Proposal.md, "fresh candidates" variant). After every
 * regular NSGA-II generation, the worst part of the population is replaced by new candidates drawn
 * from the same random generator that builds the initial population. The immigrants take part in
 * the next generation's parent selection, so the population cannot collapse onto one kind of program.
 *
 * Immigrant evaluations count against the same evaluation budget as NSGA-II.
 *
 * Selected with blocky.algorithm=IMMIGRANTS_NSGA_II. Tuning (system property):
 * blocky.immigrants.fraction (default 0.5), the share of the population replaced per generation.
 */
public class RandomImmigrantsNSGAII extends NSGAII {
	private final Initialization initialization;
	private final double fraction = Double.parseDouble(System.getProperty("blocky.immigrants.fraction", "0.5"));

	/** Takes over problem, population, archive and operators of an NSGA-II built by MOMoT. */
	public RandomImmigrantsNSGAII(final NSGAII base) {
		this(base, (Initialization) readField(org.moeaframework.algorithm.AbstractEvolutionaryAlgorithm.class, base,
				"initialization"));
	}

	private RandomImmigrantsNSGAII(final NSGAII base, final Initialization initialization) {
		super(base.getProblem(), base.getPopulation(), base.getArchive(),
				(Selection) readField(NSGAII.class, base, "selection"),
				(Variation) readField(NSGAII.class, base, "variation"), initialization);
		this.initialization = initialization;
	}

	@Override
	public void iterate() {
		super.iterate();
		if (isTerminated()) return;

		NondominatedSortingPopulation population = getPopulation();
		int populationSize = population.size();
		int immigrantCount = (int) Math.round(populationSize * Math.max(0.0, Math.min(1.0, fraction)));
		if (immigrantCount == 0) return;

		// initialize() always builds a full population; only the first immigrantCount are used and evaluated.
		Solution[] generated = initialization.initialize();
		Population immigrants = new Population();
		for (int i = 0; i < immigrantCount && i < generated.length; i++) immigrants.add(generated[i]);
		evaluateAll(immigrants);

		EpsilonBoxDominanceArchive archive = getArchive();
		if (archive != null) archive.addAll(immigrants);
		// Not truncated together: most immigrants are dominated and would be dropped again at once.
		population.truncate(populationSize - immigrants.size());
		population.addAll(immigrants);
	}

	private static Object readField(final Class<?> owner, final Object target, final String name) {
		try {
			Field f = owner.getDeclaredField(name);
			f.setAccessible(true);
			return f.get(target);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Cannot read NSGAII." + name, e);
		}
	}
}
