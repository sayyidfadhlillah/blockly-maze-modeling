package blocky_momot;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Objects;

import at.ac.tuwien.big.momot.problem.solution.TransformationSolution;
import at.ac.tuwien.big.momot.search.fitness.EGraphMultiDimensionalFitnessFunction;
import blocky.AtomicStatementKind;
import blocky.ConditionKind;

/**
 * Ensures required IN parameters of high-level units are set before Henshin execution.
 *
 * In some MOMoT/Henshin setups, parameter generators registered via ModuleManager
 * are not always materialized onto the UnitApplication for composite units before
 * preprocessEvaluation executes. Henshin then fails fast with:
 * "IN Parameter ... not set".
 *
 * This fitness function fills missing enum-choice parameters (k/cnd) right before
 * the default preprocessEvaluation calls solution.execute().
 */
public final class EnumParamPreprocessFitnessFunction extends EGraphMultiDimensionalFitnessFunction {
	// EditAnywhere (*_edit_anywhere.henshin) also takes k/cnd.
	private static final String[] TOP_UNITS = { "CreateThenInsertContainerThenPopulate", "EditAnywhere" };

	private final RandomAtomicKindLiteralValue atomicKind = new RandomAtomicKindLiteralValue();
	private final RandomConditionKindLiteralValue conditionKind = new RandomConditionKindLiteralValue();

	@Override
	public void preprocessEvaluation(final TransformationSolution solution) {
		ensureParams(solution);
		super.preprocessEvaluation(solution);
	}

	private void ensureParams(final TransformationSolution solution) {
		try {
			Object vars = callNoArg(solution, "getVariables");
			if (vars == null) vars = callNoArg(solution, "getUnitApplications");
			if (vars == null) vars = callNoArg(solution, "getUnitApplicationVariables");
			if (vars == null) {
				// Fallback: MOEA Solution-style access
				Object nObj = callNoArg(solution, "getNumberOfVariables");
				if (nObj instanceof Integer) {
					int n = ((Integer) nObj).intValue();
					for (int i = 0; i < n; i++) {
						Object var = call(solution, "getVariable", new Class<?>[] { int.class }, new Object[] { i });
						ensureOnVariable(var);
					}
				}
				return;
			}

			if (vars.getClass().isArray()) {
				final int n = Array.getLength(vars);
				for (int i = 0; i < n; i++) {
					ensureOnVariable(Array.get(vars, i));
				}
			} else if (vars instanceof Iterable<?>) {
				for (Object v : (Iterable<?>) vars) {
					ensureOnVariable(v);
				}
			}
		} catch (Throwable t) {
			System.err.println("Diag: Error in ensureParams: " + t.getMessage());
			t.printStackTrace();
		}
	}

	private void ensureOnVariable(final Object var) {
		if (var == null) return;

		try {
			Object unitApp = callNoArg(var, "getUnitApplication");
			if (unitApp == null) unitApp = callNoArg(var, "getValue");
			// Some MOMoT versions expose the UnitApplicationVariable itself as "the unit application"
			// (i.e., it has getUnit()/setParameterValue(...) directly).
			if (unitApp == null && callNoArg(var, "getUnit") != null) unitApp = var;
			if (unitApp == null) return;

			Object unit = callNoArg(unitApp, "getUnit");
			String unitName = Objects.toString(callNoArg(unit, "getName"), null);
			if (unitName == null) return;
			// Some runtimes prefix/qualify unit names; allow suffix match.
			boolean handled = false;
			for (String topUnit : TOP_UNITS) {
				if (unitName.equals(topUnit) || unitName.endsWith(topUnit)) handled = true;
			}
			if (!handled) return;

			// Only set if missing
			setIfMissing(unitApp, unit, "k", atomicKind.nextValue());
			setIfMissing(unitApp, unit, "cnd", conditionKind.nextValue());
		} catch (Throwable t) {
			System.err.println("Diag: Error in ensureOnVariable: " + t.getMessage());
		}
	}

	private void setIfMissing(final Object unitApp, final Object unit, final String paramName, final Object value) throws Exception {
		if (hasNonNullParameterValue(unitApp, paramName)) return;

		// Try setParameterValue(String, Object)
		if (tryInvoke(unitApp, "setParameterValue", new Class<?>[] { String.class, Object.class }, new Object[] { paramName, value })) {
			return;
		}

		// Fallback: setParameterValue(Parameter, Object) (Parameter impl class varies)
		Object param = findParameterByName(unit, paramName);
		if (param != null) {
			Method m = findSetParameterValueMethod(unitApp.getClass(), param.getClass());
			if (m != null) {
				m.invoke(unitApp, param, value);
			}
		}
	}

	private boolean hasNonNullParameterValue(final Object unitApp, final String paramName) {
		try {
			Method m = unitApp.getClass().getMethod("getParameterValue", String.class);
			return m.invoke(unitApp, paramName) != null;
		} catch (Throwable ignore) {
			return false;
		}
	}

	private Object findParameterByName(final Object unit, final String paramName) {
		if (unit == null) return null;
		try {
			Object params = callNoArg(unit, "getParameters");
			if (!(params instanceof Iterable<?>)) return null;
			for (Object p : (Iterable<?>) params) {
				if (p == null) continue;
				String n = Objects.toString(callNoArg(p, "getName"), null);
				if (Objects.equals(n, paramName)) return p;
			}
		} catch (Throwable ignore) {
			// ignore
		}
		return null;
	}

	private static Method findSetParameterValueMethod(final Class<?> unitAppClass, final Class<?> paramImplClass) {
		for (Method m : unitAppClass.getMethods()) {
			if (!Objects.equals(m.getName(), "setParameterValue")) continue;
			Class<?>[] pt = m.getParameterTypes();
			if (pt.length != 2) continue;
			if (pt[0].isAssignableFrom(paramImplClass) && pt[1] == Object.class) return m;
		}
		return null;
	}

	private static Object callNoArg(final Object target, final String method) {
		if (target == null) return null;
		try {
			Method m = target.getClass().getMethod(method);
			return m.invoke(target);
		} catch (Throwable ignore) {
			return null;
		}
	}

	private static Object call(final Object target, final String method, final Class<?>[] sig, final Object[] args) {
		if (target == null) return null;
		try {
			Method m = target.getClass().getMethod(method, sig);
			return m.invoke(target, args);
		} catch (Throwable ignore) {
			return null;
		}
	}

	private static boolean tryInvoke(final Object target, final String method, final Class<?>[] sig, final Object[] args) {
		try {
			Method m = target.getClass().getMethod(method, sig);
			m.invoke(target, args);
			return true;
		} catch (Throwable ignore) {
			return false;
		}
	}
}

