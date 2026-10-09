package blocky_momot;

import java.lang.reflect.Field;

import org.moeaframework.algorithm.NSGAII;
import org.moeaframework.core.EpsilonBoxDominanceArchive;
import org.moeaframework.core.Initialization;
import org.moeaframework.core.NondominatedSortingPopulation;
import org.moeaframework.core.Population;
import org.moeaframework.core.Selection;
import org.moeaframework.core.Variation;

/**
 * Random-search baseline (Exploration-Proposal.md, step 1). Every generation, a full population of
 * new candidates is drawn from the same random generator that builds NSGA-II's initial population;
 * no selection, crossover or mutation is involved in creating candidates. The best candidates seen
 * so far are kept with NSGA-II's own truncation, so results, generation counting and the first-goal
 * stop work exactly as for NSGA-II.
 *
 * Extends NSGAII only so it fits the generated runner (IRegisteredAlgorithm&lt;NSGAII&gt;).
 * Selected with blocky.algorithm=RANDOM_SEARCH.
 */
public class RandomSearchNSGAII extends NSGAII {
	private final Initialization initialization;

	/** Takes over problem, population, archive and initialization of an NSGA-II built by MOMoT. */
	public RandomSearchNSGAII(final NSGAII base) {
		this(base, (Initialization) readField(org.moeaframework.algorithm.AbstractEvolutionaryAlgorithm.class, base,
				"initialization"));
	}

	private RandomSearchNSGAII(final NSGAII base, final Initialization initialization) {
		super(base.getProblem(), base.getPopulation(), base.getArchive(),
				(Selection) readField(NSGAII.class, base, "selection"),
				(Variation) readField(NSGAII.class, base, "variation"), initialization);
		this.initialization = initialization;
	}

	@Override
	public void iterate() {
		NondominatedSortingPopulation population = getPopulation();
		EpsilonBoxDominanceArchive archive = getArchive();
		int populationSize = population.size();

		Population samples = new Population(initialization.initialize());
		evaluateAll(samples);

		if (archive != null) archive.addAll(samples);
		population.addAll(samples);
		population.truncate(populationSize);
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
