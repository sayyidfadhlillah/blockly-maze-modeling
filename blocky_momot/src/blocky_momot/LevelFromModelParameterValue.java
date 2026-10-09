package blocky_momot;

import at.ac.tuwien.big.momot.problem.unit.parameter.IParameterValue;
import at.ac.tuwien.big.momot.util.MomotUtil;
import blocky.Game;
import blocky.Level;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.henshin.interpreter.EGraph;

/**
 * Supplies IN parameter values for Henshin rules from the Level in the current model:
 * allowConditionals and allowLoops from the first level of the root Game.
 *
 * <p>Uses a thread-local EGraph so that when the graph is set (e.g. during fitness evaluation or
 * by the search framework), {@link #getInitialValue()} and {@link #nextValue()} read the level's
 * attribute. If no graph is set, returns the fallback (default true) so that rule application does
 * not fail.
 */
public class LevelFromModelParameterValue implements IParameterValue<Boolean> {

	private static final ThreadLocal<EGraph> CURRENT_GRAPH = new ThreadLocal<>();

	/** Call this when the current graph is available (e.g. before applying rules or evaluating). */
	public static void setCurrentGraph(EGraph graph) {
		if (graph != null) {
			CURRENT_GRAPH.set(graph);
		} else {
			CURRENT_GRAPH.remove();
		}
	}

	/** Clear after use to avoid leaking. */
	public static void clearCurrentGraph() {
		CURRENT_GRAPH.remove();
	}

	private final boolean forAllowConditionals;
	private final boolean fallback;

	/**
	 * @param forAllowConditionals true to supply allowConditionals, false for allowLoops
	 * @param fallback value when no graph is set (e.g. during initial random solution creation)
	 */
	public LevelFromModelParameterValue(boolean forAllowConditionals, boolean fallback) {
		this.forAllowConditionals = forAllowConditionals;
		this.fallback = fallback;
	}

	@Override
	public Boolean getInitialValue() {
		return getValue();
	}

	@Override
	public Boolean nextValue() {
		return getValue();
	}

	private Boolean getValue() {
		EGraph graph = CURRENT_GRAPH.get();
		if (graph == null) {
			return Boolean.valueOf(fallback);
		}
		EObject root = MomotUtil.getRoot(graph);
		if (!(root instanceof Game)) {
			return Boolean.valueOf(fallback);
		}
		Game game = (Game) root;
		if (game.getLevels().isEmpty()) {
			return Boolean.valueOf(fallback);
		}
		Level level = game.getLevels().get(0);
		return Boolean.valueOf(forAllowConditionals ? level.isAllowConditionals() : level.isAllowLoops());
	}
}

