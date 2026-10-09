package blocky_momot.listener;

import at.ac.tuwien.big.moea.experiment.executor.SearchExecutor;
import at.ac.tuwien.big.moea.experiment.executor.listener.AbstractProgressListener;
import at.ac.tuwien.big.momot.problem.solution.TransformationSolution;
import at.ac.tuwien.big.momot.util.MomotUtil;
import blocky.Game;
import blocky_momot.BlockyProgramDistance;
import blocky_momot.BlockyProgramMetrics;
import blocky_momot.BlockySimulator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.emf.ecore.EObject;
import org.moeaframework.algorithm.NSGAII;
import org.moeaframework.core.Algorithm;
import org.moeaframework.core.NondominatedPopulation;
import org.moeaframework.core.Solution;
import org.moeaframework.util.progress.ProgressEvent;

/**
 * MOEA progress listener that records solution formation times and notifies subscribers
 * whenever the Pareto front is updated during search execution.
 */
public class ParetoFrontPublisherListener extends AbstractProgressListener {

    public static final String ATTRIBUTE_TIME_TO_FORM = "timeToFormMs";
    public static final String ATTRIBUTE_GENERATION_TO_FORM = "generationToForm";

    private final List<IParetoFrontSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final Map<String, Long> solutionTimeToFormMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> solutionGenerationToFormMap = new ConcurrentHashMap<>();
    private final NondominatedPopulation globalParetoFront = new NondominatedPopulation();
    private final java.util.concurrent.atomic.AtomicLong firstGoalReachedTimeMs = new java.util.concurrent.atomic.AtomicLong(-1);
    private final java.util.concurrent.atomic.AtomicInteger firstGoalReachedGeneration = new java.util.concurrent.atomic.AtomicInteger(-1);
    private volatile long startTimeMs = 0;
    private volatile long lastNotifyTimeMs = 0;
    private volatile int populationSize = 50;
    private volatile Algorithm currentAlgorithm;
    private volatile boolean stopOnFirstGoal = false;

    // Non-goal archive (Improvement-Plan.md, section 3.6): with the gated objectives every goal-reaching candidate
    // dominates every non-goal one, so the Pareto front keeps at most one near-miss. The archive keeps the
    // non-goal candidates that came closest to the goal so that the solution panel can list them. It only records
    // candidates; it never feeds back into selection. blocky.nonGoalArchive = number of candidates kept (0 = off).
    private static final int OBJ_EDITS = 1;
    private static final int OBJ_ACTIONS = 2;
    private static final int OBJ_CLOSEST_TO_GOAL = 3;
    private static final int OBJ_BLOCKS = 4;
    private static final double NO_VALUE = 100000.0;
    private final List<TransformationSolution> nonGoalArchive = new ArrayList<>();
    private final Set<Solution> archiveSeen = Collections.newSetFromMap(new WeakHashMap<>());

    public ParetoFrontPublisherListener() {}

    public ParetoFrontPublisherListener(IParetoFrontSubscriber initialSubscriber) {
        if (initialSubscriber != null) {
            subscribers.add(initialSubscriber);
        }
    }

    public void setStopOnFirstGoal(boolean stopOnFirstGoal) {
        this.stopOnFirstGoal = stopOnFirstGoal;
    }

    public boolean isStopOnFirstGoal() {
        return stopOnFirstGoal;
    }

    public void setPopulationSize(int populationSize) {
        if (populationSize > 0) {
            this.populationSize = populationSize;
        }
    }

    public int getPopulationSize() {
        return populationSize;
    }

    public void setCurrentAlgorithm(Algorithm algorithm) {
        this.currentAlgorithm = algorithm;
    }

    public Algorithm getCurrentAlgorithm() {
        return currentAlgorithm;
    }

    public void addSubscriber(IParetoFrontSubscriber subscriber) {
        if (subscriber != null && !subscribers.contains(subscriber)) {
            subscribers.add(subscriber);
        }
    }

    public void removeSubscriber(IParetoFrontSubscriber subscriber) {
        if (subscriber != null) {
            subscribers.remove(subscriber);
        }
    }

    public synchronized void resetTimer() {
        this.startTimeMs = System.currentTimeMillis();
        this.lastNotifyTimeMs = 0;
        this.solutionTimeToFormMap.clear();
        this.solutionGenerationToFormMap.clear();
        this.firstGoalReachedTimeMs.set(-1);
        this.firstGoalReachedGeneration.set(-1);
        synchronized (globalParetoFront) {
            this.globalParetoFront.clear();
        }
        synchronized (nonGoalArchive) {
            this.nonGoalArchive.clear();
            this.archiveSeen.clear();
        }
    }

    /** Copies of the archived non-goal candidates; their objectives are the display values (see below). */
    public List<Solution> getNonGoalArchiveSnapshot() {
        synchronized (nonGoalArchive) {
            return new ArrayList<Solution>(nonGoalArchive);
        }
    }

    /**
     * Adds the non-goal candidates of the algorithm's population that came closest to the goal. The objective
     * values of a non-goal candidate are the gate penalty (100000) for Edits, Actions and Blocks, so two near-misses
     * with the same closestToGoal would have the same vector, and the same model file name. The archive therefore
     * stores a COPY whose Edits, Actions and Blocks are the real values, computed here after the fact. These
     * display values are not used by the search, and the original solutions are never changed.
     *
     * @return true if the archive changed
     */
    private boolean updateNonGoalArchive(Algorithm algorithm, long elapsedMs, int generation) {
        int size = Integer.getInteger("blocky.nonGoalArchive", 0);
        if (size <= 0 || !(algorithm instanceof NSGAII nsga)) {
            return false;
        }
        boolean changed = false;
        try {
            List<TransformationSolution> candidates = new ArrayList<>();
            for (Solution s : nsga.getPopulation()) {
                if (s instanceof TransformationSolution ts && !isGoalSolution(ts)
                        && ts.getNumberOfObjectives() > OBJ_CLOSEST_TO_GOAL
                        && ts.getObjective(OBJ_CLOSEST_TO_GOAL) < NO_VALUE) {
                    candidates.add(ts);
                }
            }
            candidates.sort(Comparator.comparingDouble(c -> c.getObjective(OBJ_CLOSEST_TO_GOAL)));
            synchronized (nonGoalArchive) {
                int looked = 0;
                for (TransformationSolution ts : candidates) {
                    if (looked >= 2 * size) {
                        break;
                    }
                    double closest = ts.getObjective(OBJ_CLOSEST_TO_GOAL);
                    if (nonGoalArchive.size() >= size
                            && closest > nonGoalArchive.get(nonGoalArchive.size() - 1).getObjective(OBJ_CLOSEST_TO_GOAL)) {
                        break; // sorted: no later candidate is closer than what the archive already holds
                    }
                    if (!archiveSeen.add(ts)) {
                        continue; // already looked at this solution object (elites stay in the population)
                    }
                    looked++;
                    double[] display = displayObjectives(ts);
                    if (display == null) {
                        continue;
                    }
                    TransformationSolution copy = ts.copy();
                    copy.setObjectives(display);
                    copy.setAttribute(ATTRIBUTE_TIME_TO_FORM, elapsedMs);
                    copy.setAttribute(ATTRIBUTE_GENERATION_TO_FORM, generation);
                    boolean duplicate = false;
                    for (TransformationSolution existing : nonGoalArchive) {
                        if (haveSameObjectives(existing, copy)) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) {
                        nonGoalArchive.add(copy);
                        changed = true;
                    }
                }
                if (changed) {
                    // closest to the goal first; fewer blocks first among equals
                    nonGoalArchive.sort(Comparator
                            .comparingDouble((TransformationSolution c) -> c.getObjective(OBJ_CLOSEST_TO_GOAL))
                            .thenComparingDouble(c -> c.getNumberOfObjectives() > OBJ_BLOCKS ? c.getObjective(OBJ_BLOCKS) : 0.0));
                    while (nonGoalArchive.size() > size) {
                        nonGoalArchive.remove(nonGoalArchive.size() - 1);
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[MoMoT] non-goal archive skipped: " + t);
        }
        return changed;
    }

    /** The objective vector of a non-goal candidate with Edits, Actions and Blocks replaced by their real values. */
    private static double[] displayObjectives(TransformationSolution ts) {
        EObject root = MomotUtil.getRoot(ts.execute());
        if (!(root instanceof Game game) || game.getLevels().isEmpty() || game.getLevels().get(0) == null) {
            return null;
        }
        double[] display = ts.getObjectives().clone();
        display[OBJ_EDITS] = BlockyProgramDistance.distanceToBaseline(game);
        display[OBJ_ACTIONS] = BlockySimulator.simulationSteps(game.getLevels().get(0));
        if (display.length > OBJ_BLOCKS) {
            display[OBJ_BLOCKS] = BlockyProgramMetrics.countStatements(game);
        }
        return display;
    }

    public Long getFirstGoalReachedTimeMs() {
        long t = firstGoalReachedTimeMs.get();
        return t >= 0 ? t : null;
    }

    public Integer getFirstGoalReachedGeneration() {
        int g = firstGoalReachedGeneration.get();
        return g >= 0 ? g : null;
    }

    public static boolean isGoalSolution(Solution s) {
        if (s == null) return false;
        double[] objs = s.getObjectives();
        return objs != null && objs.length > 0 && objs[0] <= -0.5;
    }

    public NondominatedPopulation getGlobalParetoFrontSnapshot() {
        synchronized (globalParetoFront) {
            return new NondominatedPopulation(globalParetoFront);
        }
    }

    public long getStartTimeMs() {
        return startTimeMs;
    }

    public Map<String, Long> getSolutionTimeToFormMap() {
        return solutionTimeToFormMap;
    }

    public Map<String, Integer> getSolutionGenerationToFormMap() {
        return solutionGenerationToFormMap;
    }

    public static String getSolutionKey(Solution s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        if (s.getObjectives() != null) {
            sb.append(Arrays.toString(s.getObjectives()));
        }
        if (s instanceof TransformationSolution ts) {
            sb.append("_len").append(ts.getSolutionLength());
        }
        return sb.toString();
    }

    public static boolean haveSameObjectives(Solution s1, Solution s2) {
        if (s1 == null || s2 == null) return false;
        double[] o1 = s1.getObjectives();
        double[] o2 = s2.getObjectives();
        if (o1 == null || o2 == null || o1.length != o2.length) return false;
        for (int i = 0; i < o1.length; i++) {
            if (Math.abs(o1[i] - o2[i]) > 1e-4) return false;
        }
        return true;
    }

    @Override
    public void update(ProgressEvent event) {
        if (event == null) {
            return;
        }

        if (isStarted(event)) {
            resetTimer();
        }

        Algorithm algorithm = this.currentAlgorithm;
        if (algorithm == null && event.getExecutor() instanceof SearchExecutor executor) {
            algorithm = executor.getAlgorithm();
        }

        if (algorithm != null) {
            NondominatedPopulation result = algorithm.getResult();
            if (result != null && !result.isEmpty()) {
                long now = System.currentTimeMillis();
                if (startTimeMs <= 0) {
                    startTimeMs = now;
                }
                long elapsed = Math.max(1, now - startTimeMs);

                int nfe = algorithm.getNumberOfEvaluations();
                if (nfe <= 0) {
                    nfe = event.getCurrentNFE();
                }
                int pop = populationSize > 0 ? populationSize : 50;
                int currentGen = nfe > 0 ? Math.max(1, (nfe + pop - 1) / pop) : 1;

                boolean newSolutionFound = false;
                synchronized (globalParetoFront) {
                    for (Solution s : result) {
                        if (s != null) {
                            String key = getSolutionKey(s);
                            Long formed = solutionTimeToFormMap.putIfAbsent(key, elapsed);
                            Integer formedGen = solutionGenerationToFormMap.putIfAbsent(key, currentGen);
                            if (formed == null || formedGen == null) {
                                newSolutionFound = true;
                            }
                            long time = (formed != null) ? formed : elapsed;
                            int gen = (formedGen != null) ? formedGen : currentGen;
                            s.setAttribute(ATTRIBUTE_TIME_TO_FORM, time);
                            s.setAttribute(ATTRIBUTE_GENERATION_TO_FORM, gen);

                            if (isGoalSolution(s)) {
                                firstGoalReachedTimeMs.accumulateAndGet(time, (curr, val) -> curr < 0 ? val : Math.min(curr, val));
                                firstGoalReachedGeneration.accumulateAndGet(gen, (curr, val) -> curr < 0 ? val : Math.min(curr, val));

                                boolean shouldStop = this.stopOnFirstGoal || Boolean.getBoolean("blocky.stopOnFirstGoal");
                                blocky_momot_runner.MomotRunContext.Config ctx = blocky_momot_runner.MomotRunContext.get();
                                if (ctx != null && ctx.stopOnFirstGoal) {
                                    shouldStop = true;
                                }
                                if (shouldStop && algorithm != null && !algorithm.isTerminated()) {
                                    algorithm.terminate();
                                }
                            }

                            // Check if a solution with identical objectives already exists in globalParetoFront
                            boolean hasIdentical = false;
                            for (Solution existing : globalParetoFront) {
                                if (haveSameObjectives(existing, s)) {
                                    hasIdentical = true;
                                    break;
                                }
                            }
                            if (!hasIdentical) {
                                if (globalParetoFront.add(s)) {
                                    newSolutionFound = true;
                                }
                            }
                        }
                    }
                }

                if (updateNonGoalArchive(algorithm, elapsed, currentGen)) {
                    newSolutionFound = true;
                }

                boolean shouldNotify = newSolutionFound
                        || (now - lastNotifyTimeMs >= 400)
                        || isSeedFinished(event)
                        || isFinished(event);

                if (shouldNotify) {
                    lastNotifyTimeMs = now;
                    NondominatedPopulation snapshot;
                    synchronized (globalParetoFront) {
                        snapshot = new NondominatedPopulation(globalParetoFront);
                    }
                    if (!snapshot.isEmpty()) {
                        for (IParetoFrontSubscriber subscriber : subscribers) {
                            try {
                                subscriber.onParetoFrontUpdated(event.getCurrentNFE(), snapshot);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
        }
    }
}
