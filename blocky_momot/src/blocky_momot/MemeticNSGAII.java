package blocky_momot;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.moeaframework.algorithm.NSGAII;
import org.moeaframework.core.EpsilonBoxDominanceArchive;
import org.moeaframework.core.Initialization;
import org.moeaframework.core.NondominatedSortingPopulation;
import org.moeaframework.core.PRNG;
import org.moeaframework.core.Population;
import org.moeaframework.core.Selection;
import org.moeaframework.core.Solution;
import org.moeaframework.core.Variation;

import at.ac.tuwien.big.momot.ModuleManager;
import at.ac.tuwien.big.momot.problem.solution.TransformationSolution;
import at.ac.tuwien.big.momot.search.algorithm.operator.mutation.TransformationParameterMutation;
import at.ac.tuwien.big.momot.search.algorithm.operator.mutation.TransformationVariableMutation;
import at.ac.tuwien.big.momot.search.solution.executor.SearchHelper;

/**
 * Memetic NSGA-II. After every regular NSGA-II generation, the best few candidates
 * (by {@link GoalFirstFitnessComparator}) are improved by a short hill climb, and the improved
 * candidates replace the originals in the population, so step-level improvements steer selection
 * and breeding (not only the final result).
 *
 * Local-search neighbors keep the candidate length fixed (required by NSGA-II's crossover): each one
 * is, with equal chance, either MOMoT's TransformationParameterMutation (one random step gets new
 * parameters, e.g. TURN_LEFT -> TURN_RIGHT) or TransformationVariableMutation (steps before a random
 * position are kept, the rest is regenerated randomly).
 * A move is taken only if strictly better. Local-search evaluations count against the same
 * evaluation budget as NSGA-II.
 *
 * Selected with blocky.algorithm=MEMETIC_NSGA_II (the game's "Alg" dropdown); default is plain NSGA-II.
 * Tuning (system properties): blocky.memetic.elites (default 2), blocky.memetic.neighbors (8),
 * blocky.memetic.steps (2).
 */
public class MemeticNSGAII extends NSGAII {
	private final Comparator<TransformationSolution> comparator;
	private final TransformationVariableMutation stepMutation;
	private final TransformationParameterMutation parameterMutation;
	private final int elites = Integer.getInteger("blocky.memetic.elites", 2);
	private final int neighbors = Integer.getInteger("blocky.memetic.neighbors", 8);
	private final int steps = Integer.getInteger("blocky.memetic.steps", 2);
	private int generations, localEvaluations, improvedCandidates;

	/** Takes over problem, population, archive and operators of an NSGA-II built by MOMoT. */
	public MemeticNSGAII(final NSGAII base, final SearchHelper searchHelper, final ModuleManager moduleManager,
			final Comparator<TransformationSolution> comparator) {
		super(base.getProblem(), base.getPopulation(), base.getArchive(),
				(Selection) readField(NSGAII.class, base, "selection"),
				(Variation) readField(NSGAII.class, base, "variation"),
				(Initialization) readField(org.moeaframework.algorithm.AbstractEvolutionaryAlgorithm.class, base, "initialization"));
		this.comparator = comparator;
		// Probability 1.0: the operator is always applied to the neighbor (it is a per-candidate probability).
		this.stepMutation = new TransformationVariableMutation(searchHelper, 1.0);
		this.parameterMutation = new TransformationParameterMutation(1.0, moduleManager);
	}

	@Override
	public void iterate() {
		super.iterate();
		generations++;
		if (elites > 0 && !isTerminated()) improveElites();
		updateStats();
	}

	/** Stats of the most recent run (MOMoT's executor does not call terminate(), so kept up to date per generation). */
	private static volatile String lastRunStats = null;

	public static String lastRunStats() {
		return lastRunStats;
	}

	/** Called when a new algorithm instance is created, so a plain NSGA-II run reports no stale stats. */
	public static void resetLastRunStats() {
		lastRunStats = null;
	}

	private void updateStats() {
		lastRunStats = "[Memetic] generations=" + generations + " localSearchEvaluations=" + localEvaluations
				+ " improvedCandidates=" + improvedCandidates + " (elites=" + elites + ", neighbors=" + neighbors
				+ ", steps=" + steps + ")";
	}

	private void improveElites() {
		NondominatedSortingPopulation population = getPopulation();
		List<TransformationSolution> ranked = new ArrayList<>();
		for (Solution s : population) ranked.add((TransformationSolution) s);
		ranked.sort(comparator);

		for (TransformationSolution elite : ranked.subList(0, Math.min(elites, ranked.size()))) {
			TransformationSolution current = elite;
			for (int step = 0; step < steps; step++) {
				TransformationSolution best = null;
				for (int n = 0; n < neighbors; n++) {
					TransformationSolution neighbor = neighbor(current);
					evaluate(neighbor);
					localEvaluations++;
					if (best == null || comparator.compare(neighbor, best) < 0) best = neighbor;
				}
				if (best == null || comparator.compare(best, current) >= 0) break; // local optimum
				current = best;
			}
			if (current != elite) {
				improvedCandidates++;
				int index = indexOf(population, elite);
				if (index >= 0) population.replace(index, current);
				EpsilonBoxDominanceArchive archive = getArchive();
				if (archive != null) archive.add(current);
			}
		}
	}

	/** Copy of s with one step's parameters changed, or with the steps after a random position regenerated. */
	private TransformationSolution neighbor(final TransformationSolution s) {
		Variation mutation = PRNG.nextBoolean() ? parameterMutation : stepMutation;
		return (TransformationSolution) mutation.evolve(new Solution[] { s.copy() })[0];
	}

	private static int indexOf(final Population population, final Solution s) {
		for (int i = 0; i < population.size(); i++) if (population.get(i) == s) return i;
		return -1;
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
