package blocky_momot;

import java.util.List;

import at.ac.tuwien.big.moea.search.algorithm.local.IFitnessComparator;
import at.ac.tuwien.big.momot.problem.solution.TransformationSolution;

/**
 * Single-criterion ordering for the local search in {@link MemeticNSGAII}, which cannot use NSGA-II's
 * Pareto ranking. Candidates are compared lexicographically, lower is better (MOEA stores maximized
 * objectives negated, so GoalReached is -1 when the goal is reached):
 *
 *   1. GoalReached     reaching the goal beats everything else
 *   2. closestToGoal   then: how close the program's path gets to the goal
 *   3. Edits           then: closeness to the user's program
 *   4. Actions         then: shorter execution
 *
 * closestToGoal comes before Edits on purpose: every inserted block increases Edits, so an
 * Edits-first order would never let an empty program grow.
 */
public final class GoalFirstFitnessComparator implements IFitnessComparator<Double, TransformationSolution> {
	private static final long serialVersionUID = 1L;
	private static final String[] PRIORITY = { "GoalReached", "closestToGoal", "Edits", "Actions" };

	private final int[] indices;

	/** @param objectiveNames objective names in the order the fitness function reports them */
	public GoalFirstFitnessComparator(final List<String> objectiveNames) {
		indices = new int[PRIORITY.length];
		for (int i = 0; i < PRIORITY.length; i++) {
			indices[i] = objectiveNames.indexOf(PRIORITY[i]);
			if (indices[i] < 0) {
				throw new IllegalArgumentException("Objective " + PRIORITY[i] + " not found in " + objectiveNames);
			}
		}
	}

	@Override
	public int compare(final TransformationSolution a, final TransformationSolution b) {
		for (int index : indices) {
			int c = Double.compare(a.getObjective(index), b.getObjective(index));
			if (c != 0) return c;
		}
		return 0;
	}

	/** Display value only (the search uses {@link #compare}): the top-priority objective still unresolved. */
	@Override
	public Double getValue(final TransformationSolution s) {
		return s.getObjective(indices[0]) * 1e9 + s.getObjective(indices[1]);
	}
}
