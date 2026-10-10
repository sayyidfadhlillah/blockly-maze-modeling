package blocky_momot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.io.File;
import java.io.IOException;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;

import blocky.AtomicStatement;
import blocky.AtomicStatementKind;
import blocky.BlockyFactory;
import blocky.BlockyPackage;
import blocky.Body;
import blocky.Cell;
import blocky.CellType;
import blocky.ConditionKind;
import blocky.Container;
import blocky.Direction;
import blocky.ExecutionTrace;
import blocky.GameState;
import blocky.GameStatus;
import blocky.GridMap;
import blocky.IfStmt;
import blocky.Level;
import blocky.Loop;
import blocky.SensorDirection;
import blocky.Statement;

/**
 * Headless simulator for Blocky maze programs. Executes a Level's solution
 * on its map and returns the final game status (WON, CRASHED, or RUNNING).
 * Used by MOMoT fitness to evaluate whether a transformed model reaches the goal.
 */
public final class BlockySimulator {

    private BlockySimulator() {}

    /**
     * By default, MoMoT evaluation is permissive and executes whatever program exists in the model,
     * even if it violates UI constraints like maxBlocks/allowed control-flow.
     *
     * Enable strict constraint enforcement via:
     *   -Dblocky.sim.enforceConstraints=true
     */
    private static final boolean ENFORCE_CONSTRAINTS =
            Boolean.parseBoolean(System.getProperty("blocky.sim.enforceConstraints", "false"));

    /**
     * The Blocky UI enforces constraints like max blocks / allowed control-flow.
     * For MOMoT fitness we must apply the same rules; otherwise we may mark an
     * illegal program as "WON" even though it can't be executed in-game.
     */
    public static boolean violatesLevelConstraints(Level level, Body solution) {
        if (level == null || solution == null) {
            return false;
        }

        int maxBlocks = level.getMaxBlocks();
        int blocks = countStatements(solution);
        if (maxBlocks > 0 && blocks > maxBlocks) {
            System.out.println("Diag: Violation - too many blocks: " + blocks + " > " + maxBlocks);
            return true;
        }

        if (!level.isAllowLoops() && containsLoop(solution)) {
            System.out.println("Diag: Violation - loops not allowed");
            return true;
        }

        if (!level.isAllowConditionals() && containsIf(solution)) {
            System.out.println("Diag: Violation - conditionals not allowed");
            return true;
        }

        return false;
    }

    private static int countStatements(Body body) {
        if (body == null) {
            return 0;
        }
        return countStatements(body.getFirstContainer());
    }

    private static int countStatements(Container c) {
        int count = 0;
        Container cur = c;
        while (cur != null) {
            Statement s = cur.getStatement();
            if (s != null) {
                count++;
                if (s instanceof Loop) {
                    count += countStatements(((Loop) s).getBody());
                } else if (s instanceof IfStmt) {
                    IfStmt i = (IfStmt) s;
                    count += countStatements(i.getThenBody());
                    count += countStatements(i.getElseBody());
                }
            }
            cur = cur.getNext();
        }
        return count;
    }

    private static boolean containsLoop(Body body) {
        if (body == null) {
            return false;
        }
        return containsLoop(body.getFirstContainer());
    }

    private static boolean containsLoop(Container c) {
        Container cur = c;
        while (cur != null) {
            Statement s = cur.getStatement();
            if (s instanceof Loop) {
                return true;
            }
            if (s instanceof IfStmt) {
                IfStmt i = (IfStmt) s;
                if (containsLoop(i.getThenBody()) || containsLoop(i.getElseBody())) {
                    return true;
                }
            }
            cur = cur.getNext();
        }
        return false;
    }

    private static boolean containsIf(Body body) {
        if (body == null) {
            return false;
        }
        return containsIf(body.getFirstContainer());
    }

    private static boolean containsIf(Container c) {
        Container cur = c;
        while (cur != null) {
            Statement s = cur.getStatement();
            if (s != null) {
                if (s instanceof IfStmt) {
                    return true;
                }
                if (s instanceof Loop) {
                    if (containsIf(((Loop) s).getBody())) {
                        return true;
                    }
                }
            }
            cur = cur.getNext();
        }
        return false;
    }

    /**
     * Load the game model from the given path, annotate all cells in all levels with
     * their distance to the goal, and save the model back to the same path.
     * This is intended to be called during MOMoT initialization so that distance-to-goal
     * values are pre-computed and stored in the model.
     *
     * @param gameXmiPath path to the game XMI file
     */
    public static void initialize(String gameXmiPath) {
        // Ensure XMI is supported.
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().putIfAbsent("xmi", new XMIResourceFactoryImpl());

        // Register Blocky metamodel.
        BlockyPackage pkg = BlockyPackage.eINSTANCE;
        EPackage.Registry.INSTANCE.put(pkg.getNsURI(), pkg);
        EPackage.Registry.INSTANCE.put(pkg.getName(), pkg);

        ResourceSet rs = new ResourceSetImpl();
        rs.getPackageRegistry().put(pkg.getNsURI(), pkg);
        rs.getPackageRegistry().put(pkg.getName(), pkg);

        URI uri;
        File f = new File(gameXmiPath);
        if (f.isAbsolute()) {
            uri = URI.createFileURI(f.getAbsolutePath());
        } else {
            uri = URI.createFileURI(new File(System.getProperty("user.dir"), gameXmiPath).getAbsolutePath());
        }

        Resource r = rs.getResource(uri, true);
        try {
            r.load(null);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load game XMI for annotation: " + uri, e);
        }

        if (r.getContents().isEmpty() || !(r.getContents().get(0) instanceof blocky.Game)) {
            return;
        }

        blocky.Game game = (blocky.Game) r.getContents().get(0);
        for (Level level : game.getLevels()) {
            annotateCells(level);
        }

        try {
            r.save(null);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save annotated game XMI: " + uri, e);
        }
    }

    /**
     * Computes the BFS distance-to-goal for every cell in the level's map and
     * sets the 'distanceToGoal' attribute on each Cell object.
     *
     * @param level the level to annotate
     */
    public static void annotateCells(Level level) {
        if (level == null || level.getMap() == null) {
            return;
        }
        GridMap map = level.getMap();
        CellType goalType = determineWinCellType(level);
        Map<Cell, Integer> dist = computeDistanceField(map, goalType);

        for (Cell c : map.getCells()) {
            if (c == null) continue;
            Integer d = dist.get(c);
            c.setDistanceToGoal(d != null ? d : -1);
        }
    }

    /**
     * Minimum BFS distance-to-goal encountered along the solution path.
     * Computed live, so cells do not need a pre-set {@code distanceToGoal}.
     * Returns {@code 0} when the path reaches the goal, and {@code penalty}
     * when the level is invalid or no goal is reachable.
     *
     * @param level the level to simulate
     * @param penalty value to return if no goal is reachable or level is invalid
     * @return minimum distance to goal encountered during simulation
     */
    public static int closestToGoalOrPenalty(Level level, int penalty) {
        return distanceToGoalOrPenalty(level, penalty);
    }

    /**
     * Run the level's solution on the level's map and return the status of the
     * last game state (WON if goal reached, CRASHED on wall or loop limit, RUNNING if incomplete).
     *
     * @param level the level with map and solution (may be null solution)
     * @return final GameStatus after execution
     */
    public static GameStatus run(Level level) {
        if (level == null || level.getMap() == null) {
            return GameStatus.CRASHED;
        }
        Body solution = level.getSolution();
        if (solution == null) {
            return GameStatus.RUNNING; // no program: never wins
        }
        if (ENFORCE_CONSTRAINTS && violatesLevelConstraints(level, solution)) {
            return GameStatus.CRASHED;
        }

        GridMap map = level.getMap();
        Cell startCell = null;
        for (Cell c : map.getCells()) {
            if (c.getType() == CellType.START) {
                startCell = c;
                break;
            }
        }
        if (startCell == null) {
            startCell = map.getCells().isEmpty() ? null : map.getCells().get(0);
        }
        if (startCell == null) {
            return GameStatus.CRASHED;
        }

        Direction startDir = determineStartOrientation(level, startCell);
        GameState initialState = BlockyFactory.eINSTANCE.createGameState();
        initialState.setStep(0);
        initialState.setPosition(startCell);
        initialState.setOrientation(startDir);
        initialState.setStatus(GameStatus.RUNNING);

        final CellType winCellType = determineWinCellType(level);

        ExecutionTrace trace = BlockyFactory.eINSTANCE.createExecutionTrace();
        trace.getStates().add(initialState);

        GameState last = executeBody(solution, initialState, trace, level, winCellType);
        return last != null ? last.getStatus() : GameStatus.CRASHED;
    }

    /**
     * Executes the level's solution and returns the executed step count if the goal is reached;
     * otherwise returns a large penalty value. Intended for MOMoT fitness evaluation.
     */
    public static int stepsToGoalOrPenalty(Level level) {
        return stepsToGoalOrPenalty(level, 100000);
    }

    /**
     * Executes the level's solution and returns the total number of executed steps/actions
     * during the simulation (whether won, crashed, or terminated).
     */
    public static int simulationSteps(Level level) {
        if (level == null || level.getMap() == null) {
            return 0;
        }
        Body solution = level.getSolution();
        if (solution == null) {
            return 0;
        }
        if (ENFORCE_CONSTRAINTS && violatesLevelConstraints(level, solution)) {
            return 100000;
        }

        GridMap map = level.getMap();
        Cell startCell = null;
        for (Cell c : map.getCells()) {
            if (c.getType() == CellType.START) {
                startCell = c;
                break;
            }
        }
        if (startCell == null) {
            startCell = map.getCells().isEmpty() ? null : map.getCells().get(0);
        }
        if (startCell == null) {
            return 0;
        }

        Direction startDir = determineStartOrientation(level, startCell);
        GameState state = BlockyFactory.eINSTANCE.createGameState();
        state.setStep(0);
        state.setPosition(startCell);
        state.setOrientation(startDir);
        state.setStatus(GameStatus.RUNNING);

        final CellType winCellType = determineWinCellType(level);
        GameState last = executeBodyLite(solution, state, level, winCellType);
        return last != null ? last.getStep() : 0;
    }

    /**
     * Executes the level's solution and returns the executed step count if the goal is reached;
     * otherwise returns {@code penalty}.
     */
    public static int stepsToGoalOrPenalty(Level level, int penalty) {
        if (level == null || level.getMap() == null) {
            return penalty;
        }
        Body solution = level.getSolution();
        if (solution == null) {
            return penalty;
        }
        if (ENFORCE_CONSTRAINTS && violatesLevelConstraints(level, solution)) {
            return penalty;
        }

        GridMap map = level.getMap();
        Cell startCell = null;
        for (Cell c : map.getCells()) {
            if (c.getType() == CellType.START) {
                startCell = c;
                break;
            }
        }
        if (startCell == null) {
            startCell = map.getCells().isEmpty() ? null : map.getCells().get(0);
        }
        if (startCell == null) {
            return penalty;
        }

        Direction startDir = determineStartOrientation(level, startCell);
        GameState state = BlockyFactory.eINSTANCE.createGameState();
        state.setStep(0);
        state.setPosition(startCell);
        state.setOrientation(startDir);
        state.setStatus(GameStatus.RUNNING);

        final CellType winCellType = determineWinCellType(level);
        GameState last = executeBodyLite(solution, state, level, winCellType);
        return (last != null && last.getStatus() == GameStatus.WON) ? last.getStep() : penalty;
    }

    /**
     * Executes the level's solution and returns the minimum BFS distance-to-goal encountered along
     * Pegman's path.
     *
     * We first compute a distance field by doing a multi-source BFS from all goal cells:
     * - If the map contains any {@code DMG} cell(s), DMG is treated as the goal.
     * - Otherwise {@code GOAL} is treated as the goal.
     *
     * While executing the program, we track the minimum distance value of any visited cell.
     * If Pegman reaches the goal, this value becomes 0.
     *
     * If the level is invalid, no goal cells exist, or the start cell is not connected to any goal,
     * returns {@code penalty}.
     */
    public static int distanceToGoalOrPenalty(Level level) {
        return distanceToGoalOrPenalty(level, 100000);
    }

    /**
     * Same as {@link #distanceToGoalOrPenalty(Level)} but with a custom penalty value.
     */
    public static int distanceToGoalOrPenalty(Level level, int penalty) {
        if (level == null || level.getMap() == null) {
            return penalty;
        }
        Body solution = level.getSolution();
        if (solution == null) {
            return penalty;
        }
        if (ENFORCE_CONSTRAINTS && violatesLevelConstraints(level, solution)) {
            return penalty;
        }

        GridMap map = level.getMap();
        Cell startCell = null;
        for (Cell c : map.getCells()) {
            if (c.getType() == CellType.START) {
                startCell = c;
                break;
            }
        }
        if (startCell == null) {
            startCell = map.getCells().isEmpty() ? null : map.getCells().get(0);
        }
        if (startCell == null) {
            return penalty;
        }

        final CellType winCellType = determineWinCellType(level);
        Map<Cell, Integer> distanceField = computeDistanceField(map, winCellType);
        if (distanceField.isEmpty()) {
            return penalty;
        }

        Direction startDir = determineStartOrientation(level, startCell);
        GameState state = BlockyFactory.eINSTANCE.createGameState();
        state.setStep(0);
        state.setPosition(startCell);
        state.setOrientation(startDir);
        state.setStatus(GameStatus.RUNNING);

        ExecResult r = executeBodyLiteWithMinDistance(solution, state, level, winCellType, distanceField);
        if (r == null) {
            return penalty;
        }
        if (r.minDistance == Integer.MAX_VALUE) {
            return penalty;
        }
        return r.minDistance;
    }

    private static Map<Cell, Integer> computeDistanceField(GridMap map, CellType goalType) {
        Map<Cell, Integer> dist = new IdentityHashMap<>();
        if (map == null || goalType == null) {
            return dist;
        }

        Deque<Cell> q = new ArrayDeque<>();
        for (Cell c : map.getCells()) {
            if (c != null && c.getType() == goalType) {
                dist.put(c, 0);
                q.addLast(c);
            }
        }
        if (q.isEmpty()) {
            return dist;
        }

        while (!q.isEmpty()) {
            Cell cur = q.removeFirst();
            int d = dist.get(cur);
            Cell[] neigh = new Cell[] { cur.getTop(), cur.getRight(), cur.getBottom(), cur.getLeft() };
            for (Cell n : neigh) {
                if (n == null) continue;
                if (n.getType() == CellType.WALL) continue;
                if (dist.containsKey(n)) continue;
                dist.put(n, d + 1);
                q.addLast(n);
            }
        }
        return dist;
    }

    private static final class ExecResult {
        final GameState last;
        final int minDistance;

        ExecResult(GameState last, int minDistance) {
            this.last = last;
            this.minDistance = minDistance;
        }
    }

    private static ExecResult executeBodyLiteWithMinDistance(
            Body body,
            GameState state,
            Level level,
            CellType winCellType,
            Map<Cell, Integer> distanceField) {
        if (body == null) return new ExecResult(state, minDistanceAt(state, distanceField, Integer.MAX_VALUE));
        return executeContainerChainLiteWithMinDistance(body.getFirstContainer(), state, level, winCellType, distanceField, Integer.MAX_VALUE);
    }

    private static ExecResult executeContainerChainLiteWithMinDistance(
            Container first,
            GameState state,
            Level level,
            CellType winCellType,
            Map<Cell, Integer> distanceField,
            int currentMin) {
        Container current = first;
        GameState last = state;
        int min = minDistanceAt(last, distanceField, currentMin);
        while (current != null && last.getStatus() == GameStatus.RUNNING && min != 0) {
            Statement stmt = current.getStatement();
            ExecResult r = executeSingleLiteWithMinDistance(stmt, last, level, winCellType, distanceField, min);
            last = r.last;
            min = r.minDistance;
            current = current.getNext();
        }
        return new ExecResult(last, min);
    }

    private static ExecResult executeSingleLiteWithMinDistance(
            Statement stmt,
            GameState prev,
            Level level,
            CellType winCellType,
            Map<Cell, Integer> distanceField,
            int currentMin) {
        // executeSingleLite already runs a whole If/Loop body, so for those only enter the statement here and
        // let the branches below run the body once while tracking the minimum distance
        boolean container = stmt instanceof Loop || stmt instanceof IfStmt;
        GameState next = container ? enterStatement(stmt, prev) : executeSingleLite(stmt, prev, level, winCellType);
        int min = minDistanceAt(next, distanceField, currentMin);
        if (min == 0 || next.getStatus() != GameStatus.RUNNING || stmt == null) {
            return new ExecResult(next, min);
        }

        if (stmt instanceof Loop) {
            Loop r = (Loop) stmt;
            GameState loop = next;
            int loopMin = min;
            GridMap map = level.getMap();
            int maxSteps = map.getWidth() * map.getHeight() * 2;
            while (loop.getStatus() == GameStatus.RUNNING && loop.getPosition().getType() != winCellType && loopMin != 0) {
                if (loop.getStep() > maxSteps) {
                    loop.setStatus(GameStatus.CRASHED);
                    break;
                }
                int previousStep = loop.getStep();
                ExecResult inner = executeBodyLiteWithMinDistance(r.getBody(), loop, level, winCellType, distanceField);
                loop = inner.last;
                loopMin = inner.minDistance;
                if (loop.getStep() == previousStep) {
                    loop.setStatus(GameStatus.CRASHED);
                    break;
                }
            }
            return new ExecResult(loop, loopMin);
        }

        if (stmt instanceof IfStmt) {
            IfStmt i = (IfStmt) stmt;
            boolean cond = checkCondition(next, i.getCondition());
            Body branch = cond ? i.getThenBody() : i.getElseBody();
            if (branch != null) {
                ExecResult inner = executeBodyLiteWithMinDistance(branch, next, level, winCellType, distanceField);
                return new ExecResult(inner.last, inner.minDistance);
            }
        }

        return new ExecResult(next, min);
    }

    private static int minDistanceAt(GameState state, Map<Cell, Integer> distanceField, int currentMin) {
        if (state == null) return currentMin;
        Cell pos = state.getPosition();
        Integer d = pos == null ? null : distanceField.get(pos);
        if (d == null) return currentMin;
        return Math.min(currentMin, d.intValue());
    }

    public static CellType determineWinCellType(Level level) {
        if (level == null || level.getMap() == null) return CellType.GOAL;
        for (Cell c : level.getMap().getCells()) {
            if (c != null && c.getType() == CellType.DMG) {
                return CellType.DMG;
            }
        }
        return CellType.GOAL;
    }

    public static Direction determineStartOrientation(Level level, Cell start) {
        if (level != null && level.eIsSet(BlockyPackage.Literals.LEVEL__START_ORIENTATION)) {
            return level.getStartOrientation();
        }
        if (start == null) return Direction.NORTH;
        if (start.getTop() != null && start.getTop().getType() != CellType.WALL) return Direction.NORTH;
        if (start.getRight() != null && start.getRight().getType() != CellType.WALL) return Direction.EAST;
        if (start.getBottom() != null && start.getBottom().getType() != CellType.WALL) return Direction.SOUTH;
        if (start.getLeft() != null && start.getLeft().getType() != CellType.WALL) return Direction.WEST;
        return Direction.NORTH;
    }

    private static SensorDirection conditionKindToSensor(ConditionKind ck) {
        // Align with generated EMF default: missing condition behaves as CHECK_FORWARD.
        if (ck == null) ck = ConditionKind.CHECK_FORWARD;
        if (ck == ConditionKind.CHECK_LEFT) return SensorDirection.LEFT;
        if (ck == ConditionKind.CHECK_RIGHT) return SensorDirection.RIGHT;
        return SensorDirection.AHEAD;
    }

    private static boolean checkCondition(GameState state, ConditionKind ck) {
        return checkSensor(state, conditionKindToSensor(ck));
    }

    private static GameState executeBody(Body body, GameState state, ExecutionTrace trace, Level level, CellType winCellType) {
        if (body == null) return state;
        return executeContainerChain(body.getFirstContainer(), state, trace, level, winCellType);
    }

    private static GameState executeBodyLite(Body body, GameState state, Level level, CellType winCellType) {
        if (body == null) return state;
        return executeContainerChainLite(body.getFirstContainer(), state, level, winCellType);
    }

    private static GameState executeContainerChain(Container first, GameState state, ExecutionTrace trace, Level level, CellType winCellType) {
        Container current = first;
        GameState last = state;
        while (current != null && last.getStatus() == GameStatus.RUNNING) {
            Statement stmt = current.getStatement();
            last = executeSingle(stmt, last, trace, level, winCellType);
            current = current.getNext();
        }
        return last;
    }

    private static GameState executeContainerChainLite(Container first, GameState state, Level level, CellType winCellType) {
        Container current = first;
        GameState last = state;
        while (current != null && last.getStatus() == GameStatus.RUNNING) {
            Statement stmt = current.getStatement();
            last = executeSingleLite(stmt, last, level, winCellType);
            current = current.getNext();
        }
        return last;
    }

    private static GameState executeSingle(Statement stmt, GameState prev, ExecutionTrace trace, Level level, CellType winCellType) {
        GameState next = BlockyFactory.eINSTANCE.createGameState();
        next.setStep(prev.getStep() + 1);
        next.setOrientation(prev.getOrientation());
        next.setPosition(prev.getPosition());
        next.setStatus(GameStatus.RUNNING);
        next.setExecutingStatement(stmt);
        next.setPrevious(prev);
        trace.getStates().add(next);

        if (stmt == null) {
            return next; // empty container -> no-op
        }

        if (stmt instanceof AtomicStatement) {
            AtomicStatement a = (AtomicStatement) stmt;
            // Align with generated EMF default: missing kind behaves as TURN_LEFT.
            AtomicStatementKind kind = a.getKind();
            if (kind == null) {
                kind = AtomicStatementKind.TURN_LEFT;
            }
            switch (kind) {
            case MOVE_FORWARD: {
                Cell target = getAdjacent(next.getPosition(), next.getOrientation());
                if (target == null || target.getType() == CellType.WALL) {
                    next.setStatus(GameStatus.CRASHED);
                } else {
                    next.setPosition(target);
                    if (target.getType() == winCellType) {
                        next.setStatus(GameStatus.WON);
                    }
                }
                break;
            }
            case TURN_LEFT:
                next.setOrientation(getRelativeDir(next.getOrientation(), SensorDirection.LEFT));
                break;
            case TURN_RIGHT:
                next.setOrientation(getRelativeDir(next.getOrientation(), SensorDirection.RIGHT));
                break;
            default:
                break;
            }
        } else if (stmt instanceof Loop) {
            Loop r = (Loop) stmt;
            GameState loop = next;
            GridMap map = level.getMap();
            int maxSteps = map.getWidth() * map.getHeight() * 2;
            while (loop.getStatus() == GameStatus.RUNNING && loop.getPosition().getType() != winCellType) {
                if (loop.getStep() > maxSteps) {
                    loop.setStatus(GameStatus.CRASHED);
                    break;
                }
                int previousStep = loop.getStep();
                loop = executeBody(r.getBody(), loop, trace, level, winCellType);
                if (loop.getStep() == previousStep) {
                    loop.setStatus(GameStatus.CRASHED);
                    break;
                }
            }
            return loop;
        } else if (stmt instanceof IfStmt) {
            IfStmt i = (IfStmt) stmt;
            boolean cond = checkCondition(next, i.getCondition());
            if (cond) {
                return executeBody(i.getThenBody(), next, trace, level, winCellType);
            }
            if (i.getElseBody() != null) {
                return executeBody(i.getElseBody(), next, trace, level, winCellType);
            }
        }
        return next;
    }

    private static GameState enterStatement(Statement stmt, GameState prev) {
        GameState next = BlockyFactory.eINSTANCE.createGameState();
        next.setStep(prev.getStep() + 1);
        next.setOrientation(prev.getOrientation());
        next.setPosition(prev.getPosition());
        next.setStatus(GameStatus.RUNNING);
        next.setExecutingStatement(stmt);
        next.setPrevious(prev);
        return next;
    }

    private static GameState executeSingleLite(Statement stmt, GameState prev, Level level, CellType winCellType) {
        GameState next = enterStatement(stmt, prev);

        if (stmt == null) {
            return next; // empty container -> no-op
        }

        if (stmt instanceof AtomicStatement) {
            AtomicStatement a = (AtomicStatement) stmt;
            // Align with generated EMF default: missing kind behaves as TURN_LEFT.
            AtomicStatementKind kind = a.getKind();
            if (kind == null) {
                kind = AtomicStatementKind.TURN_LEFT;
            }
            switch (kind) {
            case MOVE_FORWARD: {
                Cell target = getAdjacent(next.getPosition(), next.getOrientation());
                if (target == null || target.getType() == CellType.WALL) {
                    next.setStatus(GameStatus.CRASHED);
                } else {
                    next.setPosition(target);
                    if (target.getType() == winCellType) {
                        next.setStatus(GameStatus.WON);
                    }
                }
                break;
            }
            case TURN_LEFT:
                next.setOrientation(getRelativeDir(next.getOrientation(), SensorDirection.LEFT));
                break;
            case TURN_RIGHT:
                next.setOrientation(getRelativeDir(next.getOrientation(), SensorDirection.RIGHT));
                break;
            default:
                break;
            }
        } else if (stmt instanceof Loop) {
            Loop r = (Loop) stmt;
            GameState loop = next;
            GridMap map = level.getMap();
            int maxSteps = map.getWidth() * map.getHeight() * 2;
            while (loop.getStatus() == GameStatus.RUNNING && loop.getPosition().getType() != winCellType) {
                if (loop.getStep() > maxSteps) {
                    loop.setStatus(GameStatus.CRASHED);
                    break;
                }
                int previousStep = loop.getStep();
                loop = executeBodyLite(r.getBody(), loop, level, winCellType);
                if (loop.getStep() == previousStep) {
                    loop.setStatus(GameStatus.CRASHED);
                    break;
                }
            }
            return loop;
        } else if (stmt instanceof IfStmt) {
            IfStmt i = (IfStmt) stmt;
            boolean cond = checkCondition(next, i.getCondition());
            if (cond) {
                return executeBodyLite(i.getThenBody(), next, level, winCellType);
            }
            if (i.getElseBody() != null) {
                return executeBodyLite(i.getElseBody(), next, level, winCellType);
            }
        }
        return next;
    }

    private static boolean checkSensor(GameState state, SensorDirection sensor) {
        Direction actual = getRelativeDir(state.getOrientation(), sensor);
        Cell target = getAdjacent(state.getPosition(), actual);
        return target != null && target.getType() != CellType.WALL;
    }

    private static Cell getAdjacent(Cell c, Direction d) {
        switch (d) {
            case NORTH: return c.getTop();
            case SOUTH: return c.getBottom();
            case EAST:  return c.getRight();
            case WEST:  return c.getLeft();
        }
        return null;
    }

    private static Direction getRelativeDir(Direction curr, SensorDirection sensor) {
        // Align with generated EMF default: missing orientation behaves as NORTH.
        if (curr == null) curr = Direction.NORTH;
        if (sensor == SensorDirection.AHEAD) return curr;
        if (sensor == SensorDirection.LEFT) {
            switch (curr) {
                case NORTH: return Direction.WEST;
                case WEST:  return Direction.SOUTH;
                case SOUTH: return Direction.EAST;
                case EAST:  return Direction.NORTH;
            }
        } else {
            switch (curr) {
                case NORTH: return Direction.EAST;
                case EAST:  return Direction.SOUTH;
                case SOUTH: return Direction.WEST;
                case WEST:  return Direction.NORTH;
            }
        }
        return curr;
    }
}
