package blocky_momot_runner;

import at.ac.tuwien.big.moea.SearchExperiment;
import at.ac.tuwien.big.moea.experiment.executor.listener.SeedRuntimePrintListener;
import at.ac.tuwien.big.moea.print.IPopulationWriter;
import at.ac.tuwien.big.moea.print.ISolutionWriter;
import at.ac.tuwien.big.moea.search.algorithm.EvolutionaryAlgorithmFactory;
import at.ac.tuwien.big.moea.search.algorithm.LocalSearchAlgorithmFactory;
import at.ac.tuwien.big.moea.search.algorithm.provider.IRegisteredAlgorithm;
import at.ac.tuwien.big.moea.search.fitness.dimension.IFitnessDimension;
import at.ac.tuwien.big.momot.ModuleManager;
import at.ac.tuwien.big.momot.TransformationResultManager;
import at.ac.tuwien.big.momot.TransformationSearchOrchestration;
import at.ac.tuwien.big.momot.problem.solution.TransformationSolution;
import at.ac.tuwien.big.momot.problem.unit.parameter.IParameterValue;
import at.ac.tuwien.big.momot.search.algorithm.operator.mutation.TransformationParameterMutation;
import at.ac.tuwien.big.momot.search.algorithm.operator.mutation.TransformationPlaceholderMutation;
import at.ac.tuwien.big.momot.search.fitness.IEGraphMultiDimensionalFitnessFunction;
import at.ac.tuwien.big.momot.search.fitness.dimension.AbstractEGraphFitnessDimension;
import at.ac.tuwien.big.momot.search.solution.repair.ITransformationRepairer;
import at.ac.tuwien.big.momot.search.solution.repair.TransformationPlaceholderRepairer;
import at.ac.tuwien.big.momot.util.MomotUtil;
import blocky.BlockyPackage;
import blocky.Game;
import blocky.GameStatus;
import blocky.Level;
import blocky_momot.BlockyProgramDistance;
import blocky_momot.BlockyProgramMetrics;
import blocky_momot.BlockySimulator;
import blocky_momot.EnumParamPreprocessFitnessFunction;
import blocky_momot.RandomAtomicKindLiteralValue;
import blocky_momot.RandomConditionKindLiteralValue;
import java.io.File;
import java.util.Arrays;
import java.util.Objects;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.henshin.interpreter.EGraph;
import org.eclipse.xtext.xbase.lib.Exceptions;
import org.moeaframework.algorithm.NSGAII;
import org.moeaframework.core.PRNG;
import org.moeaframework.core.Population;
import org.moeaframework.core.operator.OnePointCrossover;
import org.moeaframework.core.operator.TournamentSelection;
import org.moeaframework.util.progress.ProgressListener;

@SuppressWarnings("all")
public class blocky {
  protected static String input = System.getProperty("blocky.input", "model/input/game.xmi");

  protected static final String INITIAL_MODEL = blocky.input;

  protected static final int SOLUTION_LENGTH = (BlockyProgramMetrics.inferSolutionLength(blocky.input) * 2);

  protected final String[] modules = new String[] { System.getProperty("blocky.henshin", "../blocky_model/transformations/statement_insertions_henshin_text.henshin") };

  protected final String[] unitsToRemove = new String[] { "InsertContainerIntoEmptyBody", "InsertContainerBeforeBodyHead", "InsertContainerBetweenNext", "InsertContainerAfterLast", "InsertContainerAnywhere", "PopulateEmptyContainerWithAtomic", "PopulateEmptyContainerWithLoop", "PopulateEmptyContainerWithIf", "PopulateEmptyContainerWithIfElse", "PopulateAnyEmptyContainer", "DeleteOnlyContainerFromBody", "DeleteHeadContainerWithNext", "DeleteBetweenContainerWithNext", "DeleteLastContainer", "HenshinDSL::InsertContainerIntoEmptyBody", "HenshinDSL::InsertContainerBeforeBodyHead", "HenshinDSL::InsertContainerBetweenNext", "HenshinDSL::InsertContainerAfterLast", "HenshinDSL::InsertContainerAnywhere", "HenshinDSL::PopulateEmptyContainerWithAtomic", "HenshinDSL::PopulateEmptyContainerWithLoop", "HenshinDSL::PopulateEmptyContainerWithIf", "HenshinDSL::PopulateEmptyContainerWithIfElse", "HenshinDSL::PopulateAnyEmptyContainer", "HenshinDSL::DeleteOnlyContainerFromBody", "HenshinDSL::DeleteHeadContainerWithNext", "HenshinDSL::DeleteBetweenContainerWithNext", "HenshinDSL::DeleteLastContainer", "statement_insertions_henshin_text::InsertContainerIntoEmptyBody", "statement_insertions_henshin_text::InsertContainerBeforeBodyHead", "statement_insertions_henshin_text::InsertContainerBetweenNext", "statement_insertions_henshin_text::InsertContainerAfterLast", "statement_insertions_henshin_text::InsertContainerAnywhere", "statement_insertions_henshin_text::PopulateEmptyContainerWithAtomic", "statement_insertions_henshin_text::PopulateEmptyContainerWithLoop", "statement_insertions_henshin_text::PopulateEmptyContainerWithIf", "statement_insertions_henshin_text::PopulateEmptyContainerWithIfElse", "statement_insertions_henshin_text::PopulateAnyEmptyContainer", "statement_insertions_henshin_text::DeleteOnlyContainerFromBody", "statement_insertions_henshin_text::DeleteHeadContainerWithNext", "statement_insertions_henshin_text::DeleteBetweenContainerWithNext", "statement_insertions_henshin_text::DeleteLastContainer", "statement_insertions_henshin_text::HenshinDSL::InsertContainerIntoEmptyBody", "statement_insertions_henshin_text::HenshinDSL::InsertContainerBeforeBodyHead", "statement_insertions_henshin_text::HenshinDSL::InsertContainerBetweenNext", "statement_insertions_henshin_text::HenshinDSL::InsertContainerAfterLast", "statement_insertions_henshin_text::HenshinDSL::InsertContainerAnywhere", "statement_insertions_henshin_text::HenshinDSL::PopulateEmptyContainerWithAtomic", "statement_insertions_henshin_text::HenshinDSL::PopulateEmptyContainerWithLoop", "statement_insertions_henshin_text::HenshinDSL::PopulateEmptyContainerWithIf", "statement_insertions_henshin_text::HenshinDSL::PopulateEmptyContainerWithIfElse", "statement_insertions_henshin_text::HenshinDSL::PopulateAnyEmptyContainer", "statement_insertions_henshin_text::HenshinDSL::DeleteOnlyContainerFromBody", "statement_insertions_henshin_text::HenshinDSL::DeleteHeadContainerWithNext", "statement_insertions_henshin_text::DeleteBetweenContainerWithNext", "statement_insertions_henshin_text::DeleteLastContainer", "statement_insertions_no_else::InsertContainerIntoEmptyBody", "statement_insertions_no_else::InsertContainerBeforeBodyHead", "statement_insertions_no_else::InsertContainerBetweenNext", "statement_insertions_no_else::InsertContainerAfterLast", "statement_insertions_no_else::InsertContainerAnywhere", "statement_insertions_no_else::PopulateEmptyContainerWithAtomic", "statement_insertions_no_else::PopulateEmptyContainerWithLoop", "statement_insertions_no_else::PopulateEmptyContainerWithIf", "statement_insertions_no_else::PopulateEmptyContainerWithIfElse", "statement_insertions_no_else::PopulateAnyEmptyContainer", "statement_insertions_no_else::DeleteOnlyContainerFromBody", "statement_insertions_no_else::DeleteHeadContainerWithNext", "statement_insertions_no_else::DeleteBetweenContainerWithNext", "statement_insertions_no_else::DeleteLastContainer", "statement_insertions_no_else::HenshinDSL::InsertContainerIntoEmptyBody", "statement_insertions_no_else::HenshinDSL::InsertContainerBeforeBodyHead", "statement_insertions_no_else::HenshinDSL::InsertContainerBetweenNext", "statement_insertions_no_else::HenshinDSL::InsertContainerAfterLast", "statement_insertions_no_else::HenshinDSL::InsertContainerAnywhere", "statement_insertions_no_else::HenshinDSL::PopulateEmptyContainerWithAtomic", "statement_insertions_no_else::HenshinDSL::PopulateEmptyContainerWithLoop", "statement_insertions_no_else::HenshinDSL::PopulateEmptyContainerWithIf", "statement_insertions_no_else::HenshinDSL::PopulateEmptyContainerWithIfElse", "statement_insertions_no_else::HenshinDSL::PopulateAnyEmptyContainer", "statement_insertions_no_else::HenshinDSL::DeleteOnlyContainerFromBody", "statement_insertions_no_else::HenshinDSL::DeleteHeadContainerWithNext", "statement_insertions_no_else::HenshinDSL::DeleteBetweenContainerWithNext", "statement_insertions_no_else::HenshinDSL::DeleteLastContainer", "statement_insertions_no_conds::InsertContainerIntoEmptyBody", "statement_insertions_no_conds::InsertContainerBeforeBodyHead", "statement_insertions_no_conds::InsertContainerBetweenNext", "statement_insertions_no_conds::InsertContainerAfterLast", "statement_insertions_no_conds::InsertContainerAnywhere", "statement_insertions_no_conds::PopulateEmptyContainerWithAtomic", "statement_insertions_no_conds::PopulateEmptyContainerWithLoop", "statement_insertions_no_conds::PopulateEmptyContainerWithIf", "statement_insertions_no_conds::PopulateEmptyContainerWithIfElse", "statement_insertions_no_conds::PopulateAnyEmptyContainer", "statement_insertions_no_conds::DeleteOnlyContainerFromBody", "statement_insertions_no_conds::DeleteHeadContainerWithNext", "statement_insertions_no_conds::DeleteBetweenContainerWithNext", "statement_insertions_no_conds::DeleteLastContainer", "statement_insertions_no_conds::HenshinDSL::InsertContainerIntoEmptyBody", "statement_insertions_no_conds::HenshinDSL::InsertContainerBeforeBodyHead", "statement_insertions_no_conds::HenshinDSL::InsertContainerBetweenNext", "statement_insertions_no_conds::HenshinDSL::InsertContainerAfterLast", "statement_insertions_no_conds::HenshinDSL::InsertContainerAnywhere", "statement_insertions_no_conds::HenshinDSL::PopulateEmptyContainerWithAtomic", "statement_insertions_no_conds::HenshinDSL::PopulateEmptyContainerWithLoop", "statement_insertions_no_conds::HenshinDSL::PopulateEmptyContainerWithIf", "statement_insertions_no_conds::HenshinDSL::PopulateEmptyContainerWithIfElse", "statement_insertions_no_conds::HenshinDSL::PopulateAnyEmptyContainer", "statement_insertions_no_conds::HenshinDSL::DeleteOnlyContainerFromBody", "statement_insertions_no_conds::HenshinDSL::DeleteHeadContainerWithNext", "statement_insertions_no_conds::HenshinDSL::DeleteBetweenContainerWithNext", "statement_insertions_no_conds::HenshinDSL::DeleteLastContainer", "statement_insertions_atomic_only::InsertContainerIntoEmptyBody", "statement_insertions_atomic_only::InsertContainerBeforeBodyHead", "statement_insertions_atomic_only::InsertContainerBetweenNext", "statement_insertions_atomic_only::InsertContainerAfterLast", "statement_insertions_atomic_only::InsertContainerAnywhere", "statement_insertions_atomic_only::PopulateEmptyContainerWithAtomic", "statement_insertions_atomic_only::PopulateEmptyContainerWithLoop", "statement_insertions_atomic_only::PopulateEmptyContainerWithIf", "statement_insertions_atomic_only::PopulateEmptyContainerWithIfElse", "statement_insertions_atomic_only::PopulateAnyEmptyContainer", "statement_insertions_atomic_only::DeleteOnlyContainerFromBody", "statement_insertions_atomic_only::DeleteHeadContainerWithNext", "statement_insertions_atomic_only::DeleteBetweenContainerWithNext", "statement_insertions_atomic_only::DeleteLastContainer", "statement_insertions_atomic_only::HenshinDSL::InsertContainerIntoEmptyBody", "statement_insertions_atomic_only::HenshinDSL::InsertContainerBeforeBodyHead", "statement_insertions_atomic_only::HenshinDSL::InsertContainerBetweenNext", "statement_insertions_atomic_only::HenshinDSL::InsertContainerAfterLast", "statement_insertions_atomic_only::HenshinDSL::InsertContainerAnywhere", "statement_insertions_atomic_only::HenshinDSL::PopulateEmptyContainerWithAtomic", "statement_insertions_atomic_only::HenshinDSL::PopulateEmptyContainerWithLoop", "statement_insertions_atomic_only::HenshinDSL::PopulateEmptyContainerWithIf", "statement_insertions_atomic_only::HenshinDSL::PopulateEmptyContainerWithIfElse", "statement_insertions_atomic_only::HenshinDSL::PopulateAnyEmptyContainer", "statement_insertions_atomic_only::HenshinDSL::DeleteOnlyContainerFromBody", "statement_insertions_atomic_only::HenshinDSL::DeleteHeadContainerWithNext", "statement_insertions_atomic_only::DeleteBetweenContainerWithNext", "statement_insertions_atomic_only::DeleteLastContainer", "statement_insertions_no_loops::InsertContainerIntoEmptyBody", "statement_insertions_no_loops::InsertContainerBeforeBodyHead", "statement_insertions_no_loops::InsertContainerBetweenNext", "statement_insertions_no_loops::InsertContainerAfterLast", "statement_insertions_no_loops::InsertContainerAnywhere", "statement_insertions_no_loops::PopulateEmptyContainerWithAtomic", "statement_insertions_no_loops::PopulateEmptyContainerWithLoop", "statement_insertions_no_loops::PopulateEmptyContainerWithIf", "statement_insertions_no_loops::PopulateEmptyContainerWithIfElse", "statement_insertions_no_loops::PopulateAnyEmptyContainer", "statement_insertions_no_loops::DeleteOnlyContainerFromBody", "statement_insertions_no_loops::DeleteHeadContainerWithNext", "statement_insertions_no_loops::DeleteBetweenContainerWithNext", "statement_insertions_no_loops::DeleteLastContainer", "statement_insertions_no_loops::HenshinDSL::InsertContainerIntoEmptyBody", "statement_insertions_no_loops::HenshinDSL::InsertContainerBeforeBodyHead", "statement_insertions_no_loops::HenshinDSL::InsertContainerBetweenNext", "statement_insertions_no_loops::HenshinDSL::InsertContainerAfterLast", "statement_insertions_no_loops::HenshinDSL::InsertContainerAnywhere", "statement_insertions_no_loops::HenshinDSL::PopulateEmptyContainerWithAtomic", "statement_insertions_no_loops::HenshinDSL::PopulateEmptyContainerWithLoop", "statement_insertions_no_loops::HenshinDSL::PopulateEmptyContainerWithIf", "statement_insertions_no_loops::HenshinDSL::PopulateEmptyContainerWithIfElse", "statement_insertions_no_loops::HenshinDSL::PopulateAnyEmptyContainer", "statement_insertions_no_loops::HenshinDSL::DeleteOnlyContainerFromBody", "statement_insertions_no_loops::HenshinDSL::DeleteHeadContainerWithNext", "statement_insertions_no_loops::HenshinDSL::DeleteBetweenContainerWithNext", "statement_insertions_no_loops::HenshinDSL::DeleteLastContainer" };

  protected final IEGraphMultiDimensionalFitnessFunction fitnessFunction = new EnumParamPreprocessFitnessFunction();

  protected final String _parameterValueKey_0 = "CreateThenInsertContainerThenPopulate.k";

  protected final String _parameterValueKey_1 = "CreateThenInsertContainerThenPopulate.cnd";

  protected final String _parameterValueKey_2 = "PopulateAnyEmptyContainer.k";

  protected final String _parameterValueKey_3 = "PopulateAnyEmptyContainer.cnd";

  protected final String _parameterValueKey_4 = "PopulateEmptyContainerWithAtomic.k";

  protected final String _parameterValueKey_5 = "PopulateEmptyContainerWithIf.cnd";

  protected final String _parameterValueKey_6 = "PopulateEmptyContainerWithIfElse.cnd";

  protected final String _parameterValueKey_7 = "HenshinDSL::CreateThenInsertContainerThenPopulate.k";

  protected final String _parameterValueKey_8 = "HenshinDSL::CreateThenInsertContainerThenPopulate.cnd";

  protected final String _parameterValueKey_9 = "HenshinDSL::PopulateAnyEmptyContainer.k";

  protected final String _parameterValueKey_10 = "HenshinDSL::PopulateAnyEmptyContainer.cnd";

  protected final String _parameterValueKey_11 = "HenshinDSL::PopulateEmptyContainerWithAtomic.k";

  protected final String _parameterValueKey_12 = "HenshinDSL::PopulateEmptyContainerWithIf.cnd";

  protected final String _parameterValueKey_13 = "HenshinDSL::PopulateEmptyContainerWithIfElse.cnd";

  protected final String _parameterValueKey_14 = "statement_insertions_henshin_text::CreateThenInsertContainerThenPopulate.k";

  protected final String _parameterValueKey_15 = "statement_insertions_henshin_text::CreateThenInsertContainerThenPopulate.cnd";

  protected final String _parameterValueKey_16 = "statement_insertions_henshin_text::PopulateAnyEmptyContainer.k";

  protected final String _parameterValueKey_17 = "statement_insertions_henshin_text::PopulateAnyEmptyContainer.cnd";

  protected final String _parameterValueKey_18 = "statement_insertions_henshin_text::PopulateEmptyContainerWithAtomic.k";

  protected final String _parameterValueKey_19 = "statement_insertions_henshin_text::PopulateEmptyContainerWithIf.cnd";

  protected final String _parameterValueKey_20 = "statement_insertions_henshin_text::PopulateEmptyContainerWithIfElse.cnd";

  protected final String _parameterValueKey_21 = "statement_insertions_henshin_text::HenshinDSL::CreateThenInsertContainerThenPopulate.k";

  protected final String _parameterValueKey_22 = "statement_insertions_henshin_text::HenshinDSL::CreateThenInsertContainerThenPopulate.cnd";

  protected final String _parameterValueKey_23 = "statement_insertions_no_else::CreateThenInsertContainerThenPopulate.k";

  protected final String _parameterValueKey_24 = "statement_insertions_no_else::CreateThenInsertContainerThenPopulate.cnd";

  protected final String _parameterValueKey_25 = "statement_insertions_no_else::PopulateAnyEmptyContainer.k";

  protected final String _parameterValueKey_26 = "statement_insertions_no_else::PopulateAnyEmptyContainer.cnd";

  protected final String _parameterValueKey_27 = "statement_insertions_no_else::PopulateEmptyContainerWithAtomic.k";

  protected final String _parameterValueKey_28 = "statement_insertions_no_else::PopulateEmptyContainerWithIf.cnd";

  protected final String _parameterValueKey_29 = "statement_insertions_no_else::HenshinDSL::CreateThenInsertContainerThenPopulate.k";

  protected final String _parameterValueKey_30 = "statement_insertions_no_else::HenshinDSL::CreateThenInsertContainerThenPopulate.cnd";

  protected final String _parameterValueKey_31 = "statement_insertions_no_conds::CreateThenInsertContainerThenPopulate.k";

  protected final String _parameterValueKey_32 = "statement_insertions_no_conds::PopulateAnyEmptyContainer.k";

  protected final String _parameterValueKey_33 = "statement_insertions_no_conds::PopulateEmptyContainerWithAtomic.k";

  protected final String _parameterValueKey_34 = "statement_insertions_no_conds::HenshinDSL::CreateThenInsertContainerThenPopulate.k";

  protected final String _parameterValueKey_35 = "statement_insertions_atomic_only::CreateThenInsertContainerThenPopulate.k";

  protected final String _parameterValueKey_36 = "statement_insertions_atomic_only::PopulateAnyEmptyContainer.k";

  protected final String _parameterValueKey_37 = "statement_insertions_atomic_only::PopulateEmptyContainerWithAtomic.k";

  protected final String _parameterValueKey_38 = "statement_insertions_atomic_only::HenshinDSL::CreateThenInsertContainerThenPopulate.k";

  protected final String _parameterValueKey_39 = "statement_insertions_no_loops::CreateThenInsertContainerThenPopulate.k";

  protected final String _parameterValueKey_40 = "statement_insertions_no_loops::PopulateAnyEmptyContainer.k";

  protected final String _parameterValueKey_41 = "statement_insertions_no_loops::PopulateEmptyContainerWithAtomic.k";

  protected final String _parameterValueKey_42 = "statement_insertions_no_loops::HenshinDSL::CreateThenInsertContainerThenPopulate.k";

  protected final ITransformationRepairer solutionRepairer = new TransformationPlaceholderRepairer();

  protected final int populationSize = 50;

  protected final int maxEvaluations = 2000;

  protected final int nrRuns = 10;

  protected String baseName;

  protected IParameterValue<?> _createParameterValue_0() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_1() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_2() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_3() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_4() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_5() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_6() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_7() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_8() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_9() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_10() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_11() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_12() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_13() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_14() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_15() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_16() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_17() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_18() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_19() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_20() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_21() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_22() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_23() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_24() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_25() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_26() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_27() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_28() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_29() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_30() {
    RandomConditionKindLiteralValue _randomConditionKindLiteralValue = new RandomConditionKindLiteralValue();
    return _randomConditionKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_31() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_32() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_33() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_34() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_35() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_36() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_37() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_38() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_39() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_40() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_41() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected IParameterValue<?> _createParameterValue_42() {
    RandomAtomicKindLiteralValue _randomAtomicKindLiteralValue = new RandomAtomicKindLiteralValue();
    return _randomAtomicKindLiteralValue;
  }

  protected double _createObjectiveHelper_0(final TransformationSolution solution, final EGraph graph, final EObject root) {
    double _xtrycatchfinallyexpression = (double) 0;
    try {
      double _xblockexpression = (double) 0;
      {
        final Game game = ((Game) root);
        Level _xifexpression = null;
        boolean _isEmpty = game.getLevels().isEmpty();
        if (_isEmpty) {
          _xifexpression = null;
        } else {
          _xifexpression = game.getLevels().get(0);
        }
        final Level level = _xifexpression;
        GameStatus _xifexpression_1 = null;
        boolean _equals = Objects.equals(level, null);
        if (_equals) {
          _xifexpression_1 = GameStatus.CRASHED;
        } else {
          _xifexpression_1 = BlockySimulator.run(level);
        }
        final GameStatus status = _xifexpression_1;
        double _xifexpression_2 = (double) 0;
        boolean _equals_1 = Objects.equals(status, GameStatus.WON);
        if (_equals_1) {
          _xifexpression_2 = 1.0;
        } else {
          _xifexpression_2 = 0.0;
        }
        _xblockexpression = _xifexpression_2;
      }
      _xtrycatchfinallyexpression = _xblockexpression;
    } catch (final Throwable _t) {
      if (_t instanceof Throwable) {
        _xtrycatchfinallyexpression = 0.0;
      } else {
        throw Exceptions.sneakyThrow(_t);
      }
    }
    return _xtrycatchfinallyexpression;
  }

  protected IFitnessDimension<TransformationSolution> _createObjective_0(final TransformationSearchOrchestration orchestration) {
    return new AbstractEGraphFitnessDimension("GoalReached", at.ac.tuwien.big.moea.search.fitness.dimension.IFitnessDimension.FunctionType.Maximum) {
       @Override
       protected double internalEvaluate(TransformationSolution solution) {
          EGraph graph = solution.execute();
          EObject root = MomotUtil.getRoot(graph);
          return _createObjectiveHelper_0(solution, graph, root);
       }
    };
  }

  protected double _createObjectiveHelper_1(final TransformationSolution solution, final EGraph graph, final EObject root) {
    double _xtrycatchfinallyexpression = (double) 0;
    try {
      int _distanceToBaseline = BlockyProgramDistance.distanceToBaseline(((Game) root));
      _xtrycatchfinallyexpression = ((double) _distanceToBaseline);
    } catch (final Throwable _t) {
      if (_t instanceof Throwable) {
        _xtrycatchfinallyexpression = 1000000.0;
      } else {
        throw Exceptions.sneakyThrow(_t);
      }
    }
    return _xtrycatchfinallyexpression;
  }

  protected IFitnessDimension<TransformationSolution> _createObjective_1(final TransformationSearchOrchestration orchestration) {
    return new AbstractEGraphFitnessDimension("Edits", at.ac.tuwien.big.moea.search.fitness.dimension.IFitnessDimension.FunctionType.Minimum) {
       @Override
       protected double internalEvaluate(TransformationSolution solution) {
          EGraph graph = solution.execute();
          EObject root = MomotUtil.getRoot(graph);
          return _createObjectiveHelper_1(solution, graph, root);
       }
    };
  }

  protected double _createObjectiveHelper_2(final TransformationSolution solution, final EGraph graph, final EObject root) {
    double _xtrycatchfinallyexpression = (double) 0;
    try {
      double _xblockexpression = (double) 0;
      {
        final Game game = ((Game) root);
        Level _xifexpression = null;
        boolean _isEmpty = game.getLevels().isEmpty();
        if (_isEmpty) {
          _xifexpression = null;
        } else {
          _xifexpression = game.getLevels().get(0);
        }
        final Level level = _xifexpression;
        double _xifexpression_1 = (double) 0;
        boolean _equals = Objects.equals(level, null);
        if (_equals) {
          _xifexpression_1 = 1000000.0;
        } else {
          double _xblockexpression_1 = (double) 0;
          {
            int _distanceToGoalOrPenalty = BlockySimulator.distanceToGoalOrPenalty(level);
            final double d = ((double) _distanceToGoalOrPenalty);
            double _xifexpression_2 = (double) 0;
            if ((d > 0.0)) {
              _xifexpression_2 = (1000.0 + d);
            } else {
              int _stepsToGoalOrPenalty = BlockySimulator.stepsToGoalOrPenalty(level);
              _xifexpression_2 = ((double) _stepsToGoalOrPenalty);
            }
            _xblockexpression_1 = _xifexpression_2;
          }
          _xifexpression_1 = _xblockexpression_1;
        }
        _xblockexpression = _xifexpression_1;
      }
      _xtrycatchfinallyexpression = _xblockexpression;
    } catch (final Throwable _t) {
      if (_t instanceof Throwable) {
        _xtrycatchfinallyexpression = 1000000.0;
      } else {
        throw Exceptions.sneakyThrow(_t);
      }
    }
    return _xtrycatchfinallyexpression;
  }

  protected IFitnessDimension<TransformationSolution> _createObjective_2(final TransformationSearchOrchestration orchestration) {
    return new AbstractEGraphFitnessDimension("ShortestPath", at.ac.tuwien.big.moea.search.fitness.dimension.IFitnessDimension.FunctionType.Minimum) {
       @Override
       protected double internalEvaluate(TransformationSolution solution) {
          EGraph graph = solution.execute();
          EObject root = MomotUtil.getRoot(graph);
          return _createObjectiveHelper_2(solution, graph, root);
       }
    };
  }

  protected double _createObjectiveHelper_3(final TransformationSolution solution, final EGraph graph, final EObject root) {
    double _xtrycatchfinallyexpression = (double) 0;
    try {
      double _xblockexpression = (double) 0;
      {
        final Game game = ((Game) root);
        Level _xifexpression = null;
        boolean _isEmpty = game.getLevels().isEmpty();
        if (_isEmpty) {
          _xifexpression = null;
        } else {
          _xifexpression = game.getLevels().get(0);
        }
        final Level level = _xifexpression;
        double _xifexpression_1 = (double) 0;
        boolean _equals = Objects.equals(level, null);
        if (_equals) {
          _xifexpression_1 = 1000000.0;
        } else {
          int _closestToGoalOrPenalty = BlockySimulator.closestToGoalOrPenalty(level, 100000);
          _xifexpression_1 = ((double) _closestToGoalOrPenalty);
        }
        _xblockexpression = _xifexpression_1;
      }
      _xtrycatchfinallyexpression = _xblockexpression;
    } catch (final Throwable _t) {
      if (_t instanceof Throwable) {
        _xtrycatchfinallyexpression = 1000000.0;
      } else {
        throw Exceptions.sneakyThrow(_t);
      }
    }
    return _xtrycatchfinallyexpression;
  }

  protected IFitnessDimension<TransformationSolution> _createObjective_3(final TransformationSearchOrchestration orchestration) {
    return new AbstractEGraphFitnessDimension("closestToGoal", at.ac.tuwien.big.moea.search.fitness.dimension.IFitnessDimension.FunctionType.Minimum) {
       @Override
       protected double internalEvaluate(TransformationSolution solution) {
          EGraph graph = solution.execute();
          EObject root = MomotUtil.getRoot(graph);
          return _createObjectiveHelper_3(solution, graph, root);
       }
    };
  }

  protected ModuleManager createModuleManager() {
    ModuleManager manager = new ModuleManager();
    for(String module : modules) {
       File f = new File(module);
       String targetPath = f.isAbsolute() ? f.getAbsolutePath() : f.getPath();
       manager.addModule(targetPath);
    }
    manager.removeUnits(unitsToRemove);
    manager.setParameterValue(_parameterValueKey_0, _createParameterValue_0());
    manager.setParameterValue(_parameterValueKey_1, _createParameterValue_1());
    manager.setParameterValue(_parameterValueKey_2, _createParameterValue_2());
    manager.setParameterValue(_parameterValueKey_3, _createParameterValue_3());
    manager.setParameterValue(_parameterValueKey_4, _createParameterValue_4());
    manager.setParameterValue(_parameterValueKey_5, _createParameterValue_5());
    manager.setParameterValue(_parameterValueKey_6, _createParameterValue_6());
    manager.setParameterValue(_parameterValueKey_7, _createParameterValue_7());
    manager.setParameterValue(_parameterValueKey_8, _createParameterValue_8());
    manager.setParameterValue(_parameterValueKey_9, _createParameterValue_9());
    manager.setParameterValue(_parameterValueKey_10, _createParameterValue_10());
    manager.setParameterValue(_parameterValueKey_11, _createParameterValue_11());
    manager.setParameterValue(_parameterValueKey_12, _createParameterValue_12());
    manager.setParameterValue(_parameterValueKey_13, _createParameterValue_13());
    manager.setParameterValue(_parameterValueKey_14, _createParameterValue_14());
    manager.setParameterValue(_parameterValueKey_15, _createParameterValue_15());
    manager.setParameterValue(_parameterValueKey_16, _createParameterValue_16());
    manager.setParameterValue(_parameterValueKey_17, _createParameterValue_17());
    manager.setParameterValue(_parameterValueKey_18, _createParameterValue_18());
    manager.setParameterValue(_parameterValueKey_19, _createParameterValue_19());
    manager.setParameterValue(_parameterValueKey_20, _createParameterValue_20());
    manager.setParameterValue(_parameterValueKey_21, _createParameterValue_21());
    manager.setParameterValue(_parameterValueKey_22, _createParameterValue_22());
    manager.setParameterValue(_parameterValueKey_23, _createParameterValue_23());
    manager.setParameterValue(_parameterValueKey_24, _createParameterValue_24());
    manager.setParameterValue(_parameterValueKey_25, _createParameterValue_25());
    manager.setParameterValue(_parameterValueKey_26, _createParameterValue_26());
    manager.setParameterValue(_parameterValueKey_27, _createParameterValue_27());
    manager.setParameterValue(_parameterValueKey_28, _createParameterValue_28());
    manager.setParameterValue(_parameterValueKey_29, _createParameterValue_29());
    manager.setParameterValue(_parameterValueKey_30, _createParameterValue_30());
    manager.setParameterValue(_parameterValueKey_31, _createParameterValue_31());
    manager.setParameterValue(_parameterValueKey_32, _createParameterValue_32());
    manager.setParameterValue(_parameterValueKey_33, _createParameterValue_33());
    manager.setParameterValue(_parameterValueKey_34, _createParameterValue_34());
    manager.setParameterValue(_parameterValueKey_35, _createParameterValue_35());
    manager.setParameterValue(_parameterValueKey_36, _createParameterValue_36());
    manager.setParameterValue(_parameterValueKey_37, _createParameterValue_37());
    manager.setParameterValue(_parameterValueKey_38, _createParameterValue_38());
    manager.setParameterValue(_parameterValueKey_39, _createParameterValue_39());
    manager.setParameterValue(_parameterValueKey_40, _createParameterValue_40());
    manager.setParameterValue(_parameterValueKey_41, _createParameterValue_41());
    manager.setParameterValue(_parameterValueKey_42, _createParameterValue_42());
    return manager;
  }

  protected IEGraphMultiDimensionalFitnessFunction createFitnessFunction(final TransformationSearchOrchestration orchestration) {
    IEGraphMultiDimensionalFitnessFunction function = fitnessFunction;
    function.addObjective(_createObjective_0(orchestration));
    function.addObjective(_createObjective_1(orchestration));
    function.addObjective(_createObjective_2(orchestration));
    function.addObjective(_createObjective_3(orchestration));
    function.setSolutionRepairer(solutionRepairer);
    return function;
  }

  protected IRegisteredAlgorithm<NSGAII> _createRegisteredAlgorithm_0(final TransformationSearchOrchestration orchestration, final EvolutionaryAlgorithmFactory<TransformationSolution> moea, final LocalSearchAlgorithmFactory<TransformationSolution> local) {
    TournamentSelection _tournamentSelection = new TournamentSelection(2);
    OnePointCrossover _onePointCrossover = new OnePointCrossover(1.0);
    TransformationPlaceholderMutation _transformationPlaceholderMutation = new TransformationPlaceholderMutation(0.15);
    ModuleManager _moduleManager = orchestration.getModuleManager();
    TransformationParameterMutation _transformationParameterMutation = new TransformationParameterMutation(0.1, _moduleManager);
    IRegisteredAlgorithm<NSGAII> _createNSGAII = moea.createNSGAII(_tournamentSelection, _onePointCrossover, _transformationPlaceholderMutation, _transformationParameterMutation);
    return _createNSGAII;
  }

  protected ProgressListener _createListener_0() {
    SeedRuntimePrintListener _seedRuntimePrintListener = new SeedRuntimePrintListener();
    return _seedRuntimePrintListener;
  }

  protected EGraph createInputGraph(final String initialGraph, final ModuleManager moduleManager) {
    EGraph graph = moduleManager.loadGraph(initialGraph);
    return adaptInputGraph(moduleManager, graph);
  }

  protected EGraph adaptInputGraph(final ModuleManager moduleManager, final EGraph initialGraph) {
    EGraph problemGraph = MomotUtil.copy(initialGraph);
    EObject root = MomotUtil.getRoot(problemGraph);
    return MomotUtil.createEGraph(adaptInputModel(root));
  }

  protected EObject adaptInputModel(final EObject root) {
    final Game game = ((Game) root);
    Resource _eResource = game.eResource();
    boolean _tripleNotEquals = (_eResource != null);
    if (_tripleNotEquals) {
      final ResourceSet rs = game.eResource().getResourceSet();
      rs.getPackageRegistry().put(BlockyPackage.eINSTANCE.getNsURI(), BlockyPackage.eINSTANCE);
      rs.getPackageRegistry().put(BlockyPackage.eINSTANCE.getName(), BlockyPackage.eINSTANCE);
    }
    return root;
  }

  protected TransformationSearchOrchestration createOrchestration(final String initialGraph, final int solutionLength) {
    TransformationSearchOrchestration orchestration = new TransformationSearchOrchestration();
    ModuleManager moduleManager = createModuleManager();
    EGraph graph = createInputGraph(initialGraph, moduleManager);
    orchestration.setModuleManager(moduleManager);
    orchestration.setProblemGraph(graph);
    orchestration.setSolutionLength(solutionLength);
    orchestration.setFitnessFunction(createFitnessFunction(orchestration));
    
    EvolutionaryAlgorithmFactory<TransformationSolution> moea = orchestration.createEvolutionaryAlgorithmFactory(populationSize);
    LocalSearchAlgorithmFactory<TransformationSolution> local = orchestration.createLocalSearchAlgorithmFactory();
    orchestration.addAlgorithm("NSGA_II", _createRegisteredAlgorithm_0(orchestration, moea, local));
    
    return orchestration;
  }

  protected SearchExperiment<TransformationSolution> createExperiment(final TransformationSearchOrchestration orchestration) {
    SearchExperiment<TransformationSolution> experiment = new SearchExperiment<TransformationSolution>(orchestration, maxEvaluations);
    experiment.setNumberOfRuns(nrRuns);
    experiment.addProgressListener(_createListener_0());
    return experiment;
  }

  protected void deriveBaseName(final TransformationSearchOrchestration orchestration) {
    EObject root = MomotUtil.getRoot(orchestration.getProblemGraph());
    if(root == null || root.eResource() == null || root.eResource().getURI() == null)
    	baseName = getClass().getSimpleName();
    else
    	baseName = root.eResource().getURI().trimFileExtension().lastSegment();
  }

  protected TransformationResultManager handleResults(final SearchExperiment<TransformationSolution> experiment) {
    ISolutionWriter<TransformationSolution> solutionWriter = experiment.getSearchOrchestration().createSolutionWriter();
    IPopulationWriter<TransformationSolution> populationWriter = experiment.getSearchOrchestration().createPopulationWriter();
    TransformationResultManager resultManager = new TransformationResultManager(experiment);
    Population population;
    population = 
    	TransformationResultManager.createApproximationSet(experiment, (String[])null);
    System.out.println("- Save objectives of all algorithms to 'output/objectives.pf'");
    TransformationResultManager.saveObjectives(
    	"output/objectives.pf",
    	population
    );
    System.out.println("---------------------------");
    System.out.println("Objectives of all algorithms");
    System.out.println("---------------------------");
    System.out.println(TransformationResultManager.printObjectives(
    	population
    ));
    
    population = 
    	TransformationResultManager.createApproximationSet(experiment, (String[])null);
    System.out.println("- Save solutions of all algorithms to 'output/solutions.txt'");
    TransformationResultManager.savePopulation(
    	"output/solutions.txt",
    	population,
    	populationWriter
    );
    System.out.println("- Save solutions of all algorithms to 'output/solutions.txt'");
    TransformationResultManager.saveSolutions(
    	"output/solutions/",
    	baseName,
    	MomotUtil.asIterables(
    		population,
    		TransformationSolution.class),
    	solutionWriter
    );
    
    population = 
    	TransformationResultManager.createApproximationSet(experiment, (String[])null);
    System.out.println("- Save models of all algorithms to 'output/models/'");
    TransformationResultManager.saveModels(
    	"output/models/",
    	baseName,
    	population
    );
    
    return resultManager;
  }

  public void printSearchInfo(final TransformationSearchOrchestration orchestration) {
    System.out.println("-------------------------------------------------------");
    System.out.println("Search");
    System.out.println("-------------------------------------------------------");
    System.out.println("InputModel:      " + INITIAL_MODEL);
    System.out.println("Objectives:      " + orchestration.getFitnessFunction().getObjectiveNames());
    System.out.println("NrObjectives:    " + orchestration.getNumberOfObjectives());
    System.out.println("Constraints:     " + orchestration.getFitnessFunction().getConstraintNames());
    System.out.println("NrConstraints:   " + orchestration.getNumberOfConstraints());
    System.out.println("Transformations: " + Arrays.toString(modules));
    System.out.println("Units:           " + orchestration.getModuleManager().getUnits());
    System.out.println("SolutionLength:  " + orchestration.getSolutionLength());
    System.out.println("PopulationSize:  " + populationSize);
    System.out.println("Iterations:      " + maxEvaluations / populationSize);
    System.out.println("MaxEvaluations:  " + maxEvaluations);
    System.out.println("AlgorithmRuns:   " + nrRuns);
    System.out.println("---------------------------");
  }

  public void performSearch(final String initialGraph, final int solutionLength) {
    TransformationSearchOrchestration orchestration = createOrchestration(initialGraph, solutionLength);
    deriveBaseName(orchestration);
    printSearchInfo(orchestration);
    SearchExperiment<TransformationSolution> experiment = createExperiment(orchestration);
    experiment.run();
    System.out.println("-------------------------------------------------------");
    System.out.println("Results");
    System.out.println("-------------------------------------------------------");
    handleResults(experiment);
  }

  public static void initialization() {
    System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
    final BlockyPackage pkg = BlockyPackage.eINSTANCE;
    EPackage.Registry.INSTANCE.put(pkg.getNsURI(), pkg);
    EPackage.Registry.INSTANCE.put(pkg.getName(), pkg);
    pkg.eClass();
    BlockyProgramDistance.initializeBaseline(blocky.input);
    BlockySimulator.initialize(blocky.input);
    final String seedStr = System.getProperty("blocky.seed", "0");
    try {
      final long seed = Long.parseLong(seedStr);
      if ((seed != 0L)) {
        PRNG.setSeed(seed);
      }
    } catch (final Throwable _t) {
      if (_t instanceof Exception) {
      } else {
        throw Exceptions.sneakyThrow(_t);
      }
    }
  }

  public static void main(final String... args) {
    initialization();
    blocky search = new blocky();
    search.performSearch(INITIAL_MODEL, SOLUTION_LENGTH);
  }
}
