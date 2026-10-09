package org.eclipse.emf.henshin.interpreter.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import javax.script.ScriptEngine;

import org.eclipse.emf.henshin.interpreter.ApplicationMonitor;
import org.eclipse.emf.henshin.interpreter.Assignment;
import org.eclipse.emf.henshin.interpreter.EGraph;
import org.eclipse.emf.henshin.interpreter.Engine;
import org.eclipse.emf.henshin.interpreter.InterpreterFactory;
import org.eclipse.emf.henshin.interpreter.RuleApplication;
import org.eclipse.emf.henshin.interpreter.UnitApplication;
import org.eclipse.emf.henshin.interpreter.monitoring.PerformanceMonitor;
import org.eclipse.emf.henshin.model.ConditionalUnit;
import org.eclipse.emf.henshin.model.HenshinPackage;
import org.eclipse.emf.henshin.model.IndependentUnit;
import org.eclipse.emf.henshin.model.IteratedUnit;
import org.eclipse.emf.henshin.model.LoopUnit;
import org.eclipse.emf.henshin.model.Parameter;
import org.eclipse.emf.henshin.model.ParameterKind;
import org.eclipse.emf.henshin.model.ParameterMapping;
import org.eclipse.emf.henshin.model.PriorityUnit;
import org.eclipse.emf.henshin.model.Rule;
import org.eclipse.emf.henshin.model.SequentialUnit;
import org.eclipse.emf.henshin.model.Unit;
import org.moeaframework.core.PRNG;

import static org.eclipse.emf.henshin.interpreter.util.InterpreterUtil.areNecessaryParametersSet;

/**
 * Custom UnitApplicationImpl replacing unseeded java.util.Random in Henshin
 * executeIndependentUnit with MOEA PRNG to guarantee thread-isolated determinism.
 */
public class UnitApplicationImpl extends AbstractApplicationImpl {

	protected Assignment assignment, resultAssignment;

	protected final Stack<RuleApplication> appliedRules;
	protected final Stack<RuleApplication> undoneRules;

	public UnitApplicationImpl(Engine engine) {
		super(engine);
		appliedRules = new Stack<>();
		undoneRules = new Stack<>();
	}

	public UnitApplicationImpl(Engine engine, EGraph graph, Unit unit, Assignment assignment) {
		this(engine);
		setEGraph(graph);
		setUnit(unit);
		setAssignment(assignment);
	}

	@Override
	public boolean execute(ApplicationMonitor monitor) {
		if (monitor == null) {
			monitor = InterpreterFactory.INSTANCE.createApplicationMonitor();
		} else {
			engine.setMonitor(monitor);
		}

		areNecessaryParametersSet(unit.getParameters(), unit.getName(), assignment);
		appliedRules.clear();
		undoneRules.clear();
		resultAssignment = (assignment != null)
				? new AssignmentImpl(assignment, true)
				: new AssignmentImpl(unit, true);
		return doExecute(monitor);
	}

	protected boolean doExecute(ApplicationMonitor monitor) {
		if (monitor instanceof PerformanceMonitor && !(this.unit instanceof Rule)) {
			((PerformanceMonitor) monitor).addUnitExecutionStartRecord(this.unit.getName(), this.unit.eClass().getName());
		}

		if (unit.isActivated()) {
			switch (unit.eClass().getClassifierID()) {
			case HenshinPackage.RULE:
				return executeRule(monitor);
			case HenshinPackage.INDEPENDENT_UNIT:
				return executeIndependentUnit(monitor);
			case HenshinPackage.SEQUENTIAL_UNIT:
				return executeSequentialUnit(monitor);
			case HenshinPackage.CONDITIONAL_UNIT:
				return executeConditionalUnit(monitor);
			case HenshinPackage.PRIORITY_UNIT:
				return executePriorityUnit(monitor);
			case HenshinPackage.ITERATED_UNIT:
				return executeIteratedUnit(monitor);
			case HenshinPackage.LOOP_UNIT:
				return executeLoopUnit(monitor);
			default:
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean undo(ApplicationMonitor monitor) {
		if (appliedRules.isEmpty()) {
			return true;
		}
		if (monitor == null) {
			monitor = InterpreterFactory.INSTANCE.createApplicationMonitor();
		}
		boolean success = true;
		while (!appliedRules.isEmpty()) {
			RuleApplication ruleApplication = appliedRules.pop();
			if (!ruleApplication.undo(monitor)) {
				success = false;
				break;
			}
			undoneRules.push(ruleApplication);
		}
		monitor.notifyUndo(this, success);
		return success;
	}

	@Override
	public boolean redo(ApplicationMonitor monitor) {
		if (undoneRules.isEmpty()) {
			return true;
		}
		if (monitor == null) {
			monitor = InterpreterFactory.INSTANCE.createApplicationMonitor();
		}
		boolean success = true;
		while (!undoneRules.isEmpty()) {
			RuleApplication ruleApplication = undoneRules.pop();
			if (!ruleApplication.redo(monitor)) {
				success = false;
				break;
			}
			appliedRules.push(ruleApplication);
		}
		monitor.notifyRedo(this, success);
		return success;
	}

	protected boolean executeRule(ApplicationMonitor monitor) {
		Rule rule = (Rule) unit;
		RuleApplication ruleApp = new RuleApplicationImpl(engine, graph, rule, resultAssignment);
		if (ruleApp.execute(monitor)) {
			resultAssignment = new AssignmentImpl(ruleApp.getResultMatch(), true);
			appliedRules.push(ruleApp);
			return true;
		} else {
			return false;
		}
	}

	protected boolean executeIndependentUnit(ApplicationMonitor monitor) {
		IndependentUnit indepUnit = (IndependentUnit) unit;
		List<Unit> subUnits = new ArrayList<>(indepUnit.getSubUnits());
		boolean success = false;
		while (!subUnits.isEmpty()) {
			if (monitor.isCanceled()) {
				if (monitor.isUndo()) {
					undo(monitor);
				}
				break;
			}
			int index = PRNG.nextInt(subUnits.size());
			UnitApplicationImpl unitApp = createApplicationFor(subUnits.remove(index));
			if (unitApp.execute(monitor)) {
				updateParameterValues(unitApp);
				appliedRules.addAll(unitApp.appliedRules);
				success = true;
				break;
			}
		}
		removeHiddenParametersFromResultAssignment();
		monitor.notifyExecute(this, success);
		return success;
	}

	protected boolean executeSequentialUnit(ApplicationMonitor monitor) {
		SequentialUnit seqUnit = (SequentialUnit) unit;
		boolean success = false;
		for (Unit subUnit : seqUnit.getSubUnits()) {
			if (monitor.isCanceled()) {
				if (monitor.isUndo()) {
					undo(monitor);
				}
				success = false;
				break;
			}
			UnitApplicationImpl unitApp = createApplicationFor(subUnit);
			if (unitApp.execute(monitor)) {
				success = true;
				updateParameterValues(unitApp);
				appliedRules.addAll(unitApp.appliedRules);
			} else {
				if (seqUnit.isStrict()) {
					success = false;
					if (seqUnit.isRollback()) {
						undo(monitor);
					}
					break;
				}
			}
		}
		if (seqUnit.getSubUnits().isEmpty()) {
			success = true;
		}
		removeHiddenParametersFromResultAssignment();
		monitor.notifyExecute(this, success);
		return success;
	}

	protected boolean executeConditionalUnit(ApplicationMonitor monitor) {
		boolean success = false;
		ConditionalUnit condUnit = (ConditionalUnit) unit;
		UnitApplicationImpl ifUnitApp = createApplicationFor(condUnit.getIf());
		if (ifUnitApp.execute(monitor)) {
			updateParameterValues(ifUnitApp);
			appliedRules.addAll(ifUnitApp.appliedRules);
			UnitApplicationImpl thenUnitApp = createApplicationFor(condUnit.getThen());
			success = thenUnitApp.execute(monitor);
			if (success) {
				updateParameterValues(thenUnitApp);
			}
			appliedRules.addAll(thenUnitApp.appliedRules);
		} else {
			if (condUnit.getElse() != null) {
				UnitApplicationImpl elseUnitApp = createApplicationFor(condUnit.getElse());
				success = elseUnitApp.execute(monitor);
				if (success) {
					updateParameterValues(elseUnitApp);
				}
				appliedRules.addAll(elseUnitApp.appliedRules);
			} else {
				success = true;
			}
		}
		if (monitor.isCanceled()) {
			if (monitor.isUndo()) {
				undo(monitor);
			}
			monitor.notifyExecute(this, false);
			return false;
		}
		if (!success) {
			undo(monitor);
		}
		removeHiddenParametersFromResultAssignment();
		monitor.notifyExecute(this, success);
		return success;
	}

	protected boolean executePriorityUnit(ApplicationMonitor monitor) {
		PriorityUnit priUnit = (PriorityUnit) unit;
		boolean success = false;
		for (Unit subUnit : priUnit.getSubUnits()) {
			if (monitor.isCanceled()) {
				if (monitor.isUndo()) {
					undo(monitor);
				}
				break;
			}
			UnitApplicationImpl unitApp = createApplicationFor(subUnit);
			if (unitApp.execute(monitor)) {
				updateParameterValues(unitApp);
				appliedRules.addAll(unitApp.appliedRules);
				success = true;
				break;
			}
		}
		removeHiddenParametersFromResultAssignment();
		monitor.notifyExecute(this, success);
		return success;
	}

	protected boolean executeIteratedUnit(ApplicationMonitor monitor) {
		IteratedUnit iteratedUnit = (IteratedUnit) unit;

		String itersString = iteratedUnit.getIterations();
		if (itersString == null) {
			return false;
		}
		itersString = itersString.trim();
		int iterations = 0;
		boolean ok = false;

		try {
			iterations = Integer.parseInt(itersString);
			ok = true;
		} catch (NumberFormatException e) {
		}

		if (!ok) {
			for (Parameter param : unit.getParameters()) {
				if (itersString.equals(param.getName())) {
					Object v = resultAssignment.getParameterValue(param);
					if (v instanceof Number) {
						iterations = ((Number) v).intValue();
						ok = true;
						break;
					} else {
						return false;
					}
				}
			}
		}

		if (!ok) {
			try {
				ScriptEngine scriptEngine = engine.getScriptEngine();
				for (Parameter param : unit.getParameters()) {
					scriptEngine.put(param.getName(), resultAssignment.getParameterValue(param));
				}
				Object value = scriptEngine.eval(itersString);
				if (value == null) {
					throw new RuntimeException("Error determining number of iterations for unit '" + iteratedUnit.getName() + "'");
				}
				String valueString = value.toString();
				int index = valueString.indexOf('.');
				if (index == 0) {
					valueString = "0";
				} else if (index > 0) {
					valueString = valueString.substring(0, index);
				}
				iterations = Integer.parseInt(valueString);
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage());
			}
		}

		boolean success = false;
		for (int i = 0; i < iterations; i++) {
			if (monitor.isCanceled()) {
				if (monitor.isUndo()) {
					undo(monitor);
				}
				success = false;
				break;
			}
			UnitApplicationImpl unitApp = createApplicationFor(iteratedUnit.getSubUnit());
			if (unitApp.execute(monitor)) {
				success = true;
				updateParameterValues(unitApp);
				appliedRules.addAll(unitApp.appliedRules);
			} else {
				if (iteratedUnit.isStrict()) {
					success = false;
					if (iteratedUnit.isRollback()) {
						undo(monitor);
					}
				}
				break;
			}
		}
		removeHiddenParametersFromResultAssignment();
		monitor.notifyExecute(this, success);
		return success;
	}

	protected boolean executeLoopUnit(ApplicationMonitor monitor) {
		LoopUnit loopUnit = (LoopUnit) unit;
		boolean success = true;
		while (true) {
			if (monitor.isCanceled()) {
				if (monitor.isUndo()) {
					undo(monitor);
				}
				success = false;
				break;
			}
			UnitApplicationImpl unitApp = createApplicationFor(loopUnit.getSubUnit());
			if (unitApp.execute(monitor)) {
				updateParameterValues(unitApp);
				appliedRules.addAll(unitApp.appliedRules);
			} else {
				break;
			}
		}
		removeHiddenParametersFromResultAssignment();
		monitor.notifyExecute(this, success);
		return success;
	}

	protected UnitApplicationImpl createApplicationFor(Unit subUnit) {
		if (resultAssignment == null) {
			resultAssignment = new AssignmentImpl(unit);
		}
		Assignment assign = new AssignmentImpl(subUnit);
		for (ParameterMapping mapping : unit.getParameterMappings()) {
			Parameter source = mapping.getSource();
			Parameter target = mapping.getTarget();
			if (target.getUnit() == subUnit) {
				assign.setParameterValue(target, resultAssignment.getParameterValue(source));
			}
		}
		return new UnitApplicationImpl(engine, graph, subUnit, assign);
	}

	protected void updateParameterValues(UnitApplicationImpl subUnitApp) {
		if (resultAssignment == null) {
			resultAssignment = new AssignmentImpl(unit);
		}
		for (ParameterMapping mapping : unit.getParameterMappings()) {
			Parameter source = mapping.getSource();
			Parameter target = mapping.getTarget();
			if (source.getUnit() == subUnitApp.getUnit()) {
				Parameter param = subUnitApp.getUnit().getParameter(source.getName());
				if (param != null) {
					Object value = subUnitApp.getResultAssignment().getParameterValue(param);
					if (value != null) {
						resultAssignment.setParameterValue(target, value);
					}
				}
			}
		}
	}

	@Override
	public Assignment getAssignment() {
		return assignment;
	}

	@Override
	public void setAssignment(Assignment assignment) {
		this.assignment = assignment;
	}

	@Override
	public Assignment getResultAssignment() {
		return resultAssignment;
	}

	@Override
	public Object getResultParameterValue(String paramName) {
		if (unit == null) {
			throw new RuntimeException("Transformation unit not set");
		}
		Parameter param = unit.getParameter(paramName);
		if (param == null) {
			throw new RuntimeException("No parameter \"" + paramName + "\" in transformation unit \"" + unit.getName() + "\" found");
		}
		if (resultAssignment != null) {
			return resultAssignment.getParameterValue(param);
		}
		return null;
	}

	@Override
	public void setParameterValue(String paramName, Object value) {
		if (unit == null) {
			throw new RuntimeException("Unit not set");
		}
		Parameter param = unit.getParameter(paramName);
		if (param == null) {
			throw new RuntimeException("No parameter \"" + paramName + "\" in unit \"" + unit.getName() + "\" found");
		}
		ParameterKind paramKind = param.getKind();
		if (paramKind == ParameterKind.OUT || paramKind == ParameterKind.VAR) {
			throw new RuntimeException(paramKind.getAlias() + " parameter \"" + paramName + "\" may not be set before the execution of unit \"" + unit.getName() + "\"");
		}
		if (assignment == null) {
			assignment = new AssignmentImpl(unit);
		}
		assignment.setParameterValue(param, value);
	}

	public List<RuleApplication> getAppliedRules() {
		return new ArrayList<>(appliedRules);
	}

	public List<RuleApplication> getUndoneRules() {
		return new ArrayList<>(undoneRules);
	}

	private void removeHiddenParametersFromResultAssignment() {
		for (Parameter parameter : unit.getParameters()) {
			ParameterKind parameterKind = parameter.getKind();
			if (parameterKind != ParameterKind.OUT && parameterKind != ParameterKind.INOUT
					&& parameterKind != ParameterKind.VAR) {
				resultAssignment.setParameterValue(parameter, null);
			}
		}
	}
}
