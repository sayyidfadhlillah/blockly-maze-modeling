package blocky_game;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.IdentityHashMap;

import blocky.*;
import blocky.BlockyPackage;

public class GameEngine {

    private Game currentGame;
    private Level currentLevel;
    private Resource resource;

    // Immediate feedback overlays (old vs new execution traces).
    // Computed when loading an XMI model.
    private int[][] pastPath = new int[0][0];
    private int[][] newPath = new int[0][0];

    // --- Direct Manipulation (MoMoT results) overlay diff ---
    // Baseline: predicted path for the model at the time DM was requested.
    // Solution: predicted path for a MoMoT result model loaded afterwards.
    // Used to draw common-prefix vs divergence overlays.
    private boolean dmCompareArmed;
    private int dmStartX;
    private int dmStartY;
    private Direction dmStartDir;
    private int[][] dmBaselinePath = new int[0][0];
    private int[][] dmSolutionPath = new int[0][0];
    private int dmCommonLen;
    // For MoMoT loads: the last common cell and its state index on the solution trace.
    private int dmAlignedX;
    private int dmAlignedY;
    private int dmAlignedStateIndex;
    private boolean dmAlignedValid;

    // --- Debugger session state (Java-driven stepping) ---
    private ExecutionTrace debugTrace;
    private List<String> debugLogLines;
    private int debugIndex; // index into debugTrace.getStates()
    private boolean debugPaused;
    private boolean debugDirtySolution;
    private int debugStartX;
    private int debugStartY;
    private Direction debugStartDir;
    private int debugCurrentX;
    private int debugCurrentY;
    private Direction debugCurrentDir;
    private boolean debugSessionActive;
    // Immediate Feedback during debugging (paused edits): remember the last executed prefix path
    // so we can compare it with the newly predicted path after edits.
    private int[][] debugPastPrefixPath = new int[0][0];
    private String debugImmediateFeedbackNote;

    // Map EMF Statement instances (identity) to Blockly block ids from the WebView workspace XML.
    // This enables Maze-style highlighting of the currently executing block while debugging.
    private final IdentityHashMap<Statement, String> stmtToBlocklyId = new IdentityHashMap<>();

    private Cell findCellByXY(int x, int y) {
        if (currentLevel == null || currentLevel.getMap() == null || currentLevel.getMap().getCells() == null) return null;
        for (Cell c : currentLevel.getMap().getCells()) {
            if (c != null && c.getX() == x && c.getY() == y) return c;
        }
        return null;
    }

    private static void ensureLevelHasNonNullSolution(Level level) {
        if (level == null) return;
        if (level.getSolution() != null) return;
        Body b = BlockyFactory.eINSTANCE.createBody();
        Container c = BlockyFactory.eINSTANCE.createContainer();
        // Keep statement unset (null) — represents an empty program.
        b.setFirstContainer(c);
        level.setSolution(b);
    }

    private static void ensureGameHasNonNullSolutions(Game game) {
        if (game == null || game.getLevels() == null) return;
        for (Level lvl : game.getLevels()) {
            ensureLevelHasNonNullSolution(lvl);
        }
    }

    private void clearDirectManipulationGoal() {
        if (currentLevel == null || currentLevel.getMap() == null) return;
        for (Cell c : currentLevel.getMap().getCells()) {
            if (c != null && c.getType() == CellType.DMG) {
                c.setType(CellType.EMPTY);
            }
        }
    }

    /**
     * Teleport pegman to a specific map cell for direct manipulation.
     * This is allowed only onto EMPTY or GOAL cells.
     *
     * Also updates the engine's notion of the current situation by creating a single-state trace
     * at the target position and direction.
     *
     * @param x cell x
     * @param y cell y
     * @param t direction code (0=N,1=E,2=S,3=W)
     */
    public void teleportPegman(int x, int y, int t) {
        if (currentLevel == null || currentLevel.getMap() == null) return;

        Cell target = findCellByXY(x, y);
        if (target == null) {
            System.err.println("[GameEngine] teleportPegman rejected: no cell at x=" + x + " y=" + y);
            return;
        }
        if (!(target.getType() == CellType.EMPTY || target.getType() == CellType.GOAL || target.getType() == CellType.DMG)) {
            System.err.println("[GameEngine] teleportPegman rejected: target type=" + target.getType() + " at x=" + x + " y=" + y);
            return;
        }

        Direction dir = blocky_game.DebuggingService.tToDirection(t);

        // Mark DM goal: the clicked cell becomes a DMG marker unless it's already the real GOAL.
        // DMG is an intermediate target for synthesis and UI display; GOAL remains the semantic level goal.
        clearDirectManipulationGoal();
        if (target.getType() == CellType.EMPTY) {
            target.setType(CellType.DMG);
        }

        // Update debugger snapshot (so Resume/Step continues from here if paused).
        debugCurrentX = x;
        debugCurrentY = y;
        debugCurrentDir = dir;

        // If no debug session ever started, also treat this as the current start snapshot.
        if (debugTrace == null) {
            debugStartX = x;
            debugStartY = y;
            debugStartDir = dir;
        }

        // Replace traces with a minimal "current situation" trace at the teleported state.
        currentLevel.getTraces().clear();
        ExecutionTrace trace = BlockyFactory.eINSTANCE.createExecutionTrace();
        currentLevel.getTraces().add(trace);
        GameState initialState = BlockyFactory.eINSTANCE.createGameState();
        initialState.setStep(0);
        initialState.setPosition(target);
        initialState.setOrientation(dir);
        initialState.setStatus(GameStatus.RUNNING);
        trace.getStates().add(initialState);

        // Capture DM baseline path for MoMoT result comparison (best-effort).
        // IMPORTANT: compare from the REAL level start, not from the DM-teleported state.
        // Otherwise the "common prefix" degenerates to a single point (the teleported DMG cell),
        // and Pegman cannot be repositioned to a meaningful last-common cell after loading a MoMoT model.
        Cell startCell = getStartCell(currentLevel.getMap());
        if (startCell != null) {
            dmStartX = startCell.getX();
            dmStartY = startCell.getY();
        } else {
            dmStartX = x;
            dmStartY = y;
        }
        dmStartDir = (currentLevel != null && currentLevel.eIsSet(BlockyPackage.Literals.LEVEL__START_ORIENTATION))
                ? currentLevel.getStartOrientation()
                : dir;
        try {
            blocky_game.DebuggingService.DebugTraceResult base =
                    blocky_game.DebuggingService.computeTraceFromState(currentLevel, dmStartX, dmStartY, dmStartDir);
            dmBaselinePath = compressTracePositions(base.trace, base.trace != null && base.trace.getStates() != null ? base.trace.getStates().size() - 1 : 0);
        } catch (Exception e) {
            dmBaselinePath = new int[0][0];
        }
        // Reset previous solution diff (will be computed when a MoMoT solution is loaded).
        dmSolutionPath = new int[0][0];
        dmCommonLen = 0;
        dmAlignedValid = false;

        // If a debug session is active, recompute trace from the new snapshot when paused/next step.
        if (debugSessionActive) {
            debugDirtySolution = true;
        }

        // Persist a direct manipulation request model for downstream tools (MoMoT).
        saveDirectManipulationRequestXmi();
        saveMomotInputRequestXmi();
    }

    /**
     * Saves a deterministic XMI snapshot representing the current situation after direct manipulation.
     * This does not change the engine's primary {@link #resource} URI.
     */
    public void saveDirectManipulationRequestXmi() {
        try {
            File out = new File("blocky_game/direct_manipulation_request.xmi");
            if (!out.getParentFile().exists()) out = new File("direct_manipulation_request.xmi");

            BlockyPackage.eINSTANCE.eClass();
            Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl());

            ResourceSet resSet = new ResourceSetImpl();
            Resource outRes = resSet.createResource(URI.createFileURI(out.getAbsolutePath()));

            Game snapshot = currentGame != null ? EcoreUtil.copy(currentGame) : null;
            ensureGameHasNonNullSolutions(snapshot);
            if (snapshot != null) {
                outRes.getContents().add(snapshot);
                outRes.save(null);
                System.out.println("[GameEngine] Direct manipulation request saved to: " + outRes.getURI().toFileString());
            }
        } catch (Exception e) {
            System.err.println("[GameEngine] Direct manipulation request save failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Saves the DM request model to the default MoMoT input location.
     * This is intended to be used as the search.model.file for automated MoMoT runs.
     */
    public void saveMomotInputRequestXmi() {
        try {
            File out = new File("blocky_momot/model/input/direct_manipulation_request.xmi");
            if (!out.getParentFile().exists()) {
                out = new File("../blocky_momot/model/input/direct_manipulation_request.xmi");
            }
            if (!out.getParentFile().exists()) {
                // best-effort: fall back to repo root
                out = new File("direct_manipulation_request_momot.xmi");
            } else {
                out.getParentFile().mkdirs();
            }

            BlockyPackage.eINSTANCE.eClass();
            Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl());

            ResourceSet resSet = new ResourceSetImpl();
            Resource outRes = resSet.createResource(URI.createFileURI(out.getAbsolutePath()));

            Game snapshot = currentGame != null ? EcoreUtil.copy(currentGame) : null;
            ensureGameHasNonNullSolutions(snapshot);
            if (snapshot != null) {
                outRes.getContents().add(snapshot);
                outRes.save(null);
                System.out.println("[GameEngine] MoMoT DM request saved to: " + outRes.getURI().toFileString());
            }
        } catch (Exception e) {
            System.err.println("[GameEngine] MoMoT DM request save failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void initializeGame() {
        BlockyPackage.eINSTANCE.eClass();
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl());

        ResourceSet resSet = new ResourceSetImpl();
        java.io.File xmiFile = new java.io.File("blocky_game/model/input/1.xmi").getAbsoluteFile();
        if (!xmiFile.exists()) {
            xmiFile = new java.io.File("model/input/1.xmi").getAbsoluteFile();
        }
        if (!xmiFile.exists()) {
            xmiFile = new java.io.File("blocky_game/level1_state.xmi").getAbsoluteFile();
        }
        if (!xmiFile.exists()) {
            xmiFile = new java.io.File("level1_state.xmi").getAbsoluteFile();
        }
        System.out.println("[GameEngine] Initial XMI file path: " + xmiFile.getAbsolutePath());
        if (xmiFile.exists()) {
            try {
                loadFromFile(xmiFile);
                return;
            } catch (Exception e) {
                System.err.println("[GameEngine] Could not load default initial level from " + xmiFile + ": " + e.getMessage());
            }
        }

        resource = resSet.createResource(URI.createFileURI(xmiFile.getAbsolutePath()));

        // Root object is now Game, which contains one or more Levels.
        currentGame = BlockyFactory.eINSTANCE.createGame();
        currentLevel = BlockyFactory.eINSTANCE.createLevel();
        currentGame.getLevels().add(currentLevel);
        resource.getContents().add(currentGame);
    }

    public void setMapFromJson(String mapJson) {
        // mapJson looks like:
        // [[0,0,0...],[...],...]
        mapJson = mapJson.replaceAll("\\s+", "");
        if (mapJson.startsWith("[[")) {
            mapJson = mapJson.substring(2, mapJson.length() - 2);
        }

        String[] rowStrs = mapJson.split("\\],\\[");
        int height = rowStrs.length;
        int width = rowStrs[0].split(",").length;

        // Clear execution traces: they reference Cell instances from the previous map, which would become dangling after we replace the map and cause DanglingHREFException on save.
        currentLevel.getTraces().clear();

        // Clear any previously-synced start orientation so syncLevelMeta will set it fresh.
        currentLevel.eUnset(BlockyPackage.Literals.LEVEL__START_ORIENTATION);

        // Preserve markers that the WebView doesn't know about or swaps during DM.
        // We only do this if we were already in DM mode (indicated by an existing DMG cell).
        Cell oldDmg = getDmgCell(currentLevel.getMap());
        Cell oldGoal = (oldDmg != null) ? getGoalCell(currentLevel.getMap()) : null;

        // Detection of level switch: if the incoming map has a goal (value 3) at a position 
        // that doesn't match our current DMG, it's likely a level switch. 
        // In that case, discard DM state.
        if (oldDmg != null) {
            int newGoalX = -1, newGoalY = -1;
            String[] rows = mapJson.split("\\],\\[");
            for (int r = 0; r < rows.length; r++) {
                String[] cols = rows[r].split(",");
                for (int c = 0; cols != null && c < cols.length; c++) {
                    if ("3".equals(cols[c].trim())) {
                        newGoalX = c; newGoalY = r; break;
                    }
                }
                if (newGoalX != -1) break;
            }
            if (newGoalX != -1 && (newGoalX != oldDmg.getX() || newGoalY != oldDmg.getY())) {
                System.out.println("[GameEngine] Level switch detected via map goal position. Resetting DM state.");
                oldDmg = null;
                oldGoal = null;
            }
        }

        GridMap map = BlockyFactory.eINSTANCE.createGridMap();
        map.setWidth(width);
        map.setHeight(height);
        currentLevel.setMap(map);

        Cell[][] grid = new Cell[width][height];

        for (int y = 0; y < height; y++) {
            String[] colStrs = rowStrs[y].split(",");
            for (int x = 0; x < width; x++) {
                int val = Integer.parseInt(colStrs[x]);
                Cell cell = BlockyFactory.eINSTANCE.createCell();
                cell.setX(x);
                cell.setY(y);

                switch (val) {
                    case 0:
                        cell.setType(CellType.WALL);
                        break;
                    case 1:
                        cell.setType(CellType.EMPTY);
                        break;
                    case 2:
                        cell.setType(CellType.START);
                        break;
                    case 3:
                        // Value 3 is GOAL in WebView. 
                        // If we are restoring from a custom model (oldGoal != null), 
                        // we treat all incoming goals as EMPTY first, then restore the correct one later.
                        cell.setType(oldGoal != null ? CellType.EMPTY : CellType.GOAL);
                        break;
                }

                grid[x][y] = cell;
                map.getCells().add(cell);
            }
        }

        // Restore DMG and original GOAL markers if they were swapped for the WebView.
        if (oldDmg != null) {
            Cell newDmgCell = findCellByXY(oldDmg.getX(), oldDmg.getY());
            // In DM mode, the WebView sees DMG as value 3 (GOAL).
            if (newDmgCell != null && newDmgCell.getType() == CellType.GOAL) {
                newDmgCell.setType(CellType.DMG);
            }
        }
        if (oldGoal != null) {
            Cell newGoalCell = findCellByXY(oldGoal.getX(), oldGoal.getY());
            // In DM mode, the WebView sees the original GOAL as value 1 (EMPTY).
            if (newGoalCell != null && newGoalCell.getType() == CellType.EMPTY) {
                newGoalCell.setType(CellType.GOAL);
            }
        }

        // Link cells
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Cell cell = grid[x][y];
                if (y > 0)
                    cell.setTop(grid[x][y - 1]);
                if (y < height - 1)
                    cell.setBottom(grid[x][y + 1]);
                if (x > 0)
                    cell.setLeft(grid[x - 1][y]);
                if (x < width - 1)
                    cell.setRight(grid[x + 1][y]);
            }
        }
    }

    public void cycleCellType(int x, int y) {
        int index = y * currentLevel.getMap().getWidth() + x;
        Cell cell = currentLevel.getMap().getCells().get(index);

        switch (cell.getType()) {
            case EMPTY:
                cell.setType(CellType.WALL);
                break;
            case WALL:
                setUniqueCellType(cell, CellType.START);
                break;
            case START:
                setUniqueCellType(cell, CellType.GOAL);
                break;
            case GOAL:
                cell.setType(CellType.EMPTY);
                break;
        }
    }

    private void setUniqueCellType(Cell targetCell, CellType type) {
        for (Cell c : currentLevel.getMap().getCells()) {
            if (c.getType() == type) {
                c.setType(CellType.EMPTY);
            }
        }
        targetCell.setType(type);
    }

    // --- Program / statement management (Body + linked Statements) ---

    public void rebuildProgram(java.util.List<Map<String, Object>> blockData) {
        System.out.println("[GameEngine] Rebuilding model solution...");
        // If the user is editing while paused in the debugger, we need to recompute on the next
        // Resume/Step using the updated solution blocks.
        if (debugTrace != null && debugPaused) {
            debugDirtySolution = true;
        }

        currentLevel.getTraces().clear(); // Clear old traces—they reference old solution blocks
        currentLevel.setSolution(null);
        stmtToBlocklyId.clear();
        if (blockData == null || blockData.isEmpty()) {
            System.out.println("[GameEngine] Program cleared.");
            return;
        }

        currentLevel.setSolution(buildSolutionBody(blockData));
        System.out.println("[GameEngine] Solution rebuilt. Main sequence length: " + blockData.size());
    }

    private static Body emptyBody() {
        return BlockyFactory.eINSTANCE.createBody();
    }

    private static Body wrapContainerChain(Container head) {
        Body b = BlockyFactory.eINSTANCE.createBody();
        b.setFirstContainer(head);
        return b;
    }

    private Container createContainerChainFromData(Map<String, Object> data) {
        if (data == null) return null;

        Statement stmt = createStatementFromData(data);
        if (stmt == null) return null;

        Container c = BlockyFactory.eINSTANCE.createContainer();
        c.setStatement(stmt);

        Map<String, Object> nextData = (Map<String, Object>) data.get("next");
        if (nextData != null) {
            c.setNext(createContainerChainFromData(nextData));
        }
        return c;
    }

    private Statement createStatementFromData(Map<String, Object> data) {
        String type = (String) data.get("type");
        String blockId = null;
        try {
            Object idObj = data.get("id");
            blockId = idObj != null ? String.valueOf(idObj) : null;
        } catch (Exception ignored) {
        }
        Statement stmt = null;

        if ("maze_moveForward".equals(type) || "move_forward".equals(type)) {
            AtomicStatement a = BlockyFactory.eINSTANCE.createAtomicStatement();
            a.setKind(AtomicStatementKind.MOVE_FORWARD);
            stmt = a;
        } else if ("maze_turn".equals(type) || "turn_left".equals(type) || "turn_right".equals(type)) {
            String dir = (String) data.get("DIR");
            System.out.println("[GameEngine]   Turn block: type=" + type + ", DIR field=" + dir + ", all keys=" + data.keySet());
            if (dir == null) {
                dir = type;
            }
            boolean right = dir.toLowerCase().contains("right");
            AtomicStatement a = BlockyFactory.eINSTANCE.createAtomicStatement();
            a.setKind(right ? AtomicStatementKind.TURN_RIGHT : AtomicStatementKind.TURN_LEFT);
            stmt = a;
            System.out.println("[GameEngine]   -> resolved dir=" + dir + " -> " + a.getKind());
        } else if ("maze_forever".equals(type) || "repeat_until_goal".equals(type)) {
            Loop r = BlockyFactory.eINSTANCE.createLoop();
            Map<String, Object> bodyData = (Map<String, Object>) data.get("body");
            r.setBody(bodyData != null ? wrapContainerChain(createContainerChainFromData(bodyData)) : emptyBody());
            stmt = r;
        } else if (type != null && (type.startsWith("maze_if") || type.startsWith("if_path"))) {
            IfStmt i = BlockyFactory.eINSTANCE.createIfStmt();
            String dir = (String) data.get("DIR");
            ConditionKind ck = dir != null ? parseCondition(dir) : parseCondition(type);
            i.setCondition(ck);
            Map<String, Object> thenData = (Map<String, Object>) data.get("body");
            if (thenData == null) {
                thenData = (Map<String, Object>) data.get("then");
            }
            i.setThenBody(wrapContainerChain(thenData != null ? createContainerChainFromData(thenData) : null));
            Map<String, Object> elseData = (Map<String, Object>) data.get("elseBranch");
            if (elseData == null) {
                elseData = (Map<String, Object>) data.get("else");
            }
            if (elseData != null) {
                i.setElseBody(wrapContainerChain(createContainerChainFromData(elseData)));
            }
            stmt = i;
        }

        if (stmt != null && blockId != null && !blockId.isEmpty()) {
            stmtToBlocklyId.put(stmt, blockId);
        }
        return stmt;
    }

    private Body buildSolutionBody(java.util.List<Map<String, Object>> dataList) {
        // Blockly typically serializes the whole program as ONE top-level <block>
        // with a linked <next> chain. Our XML parser preserves that as nested "next" maps,
        // so we must convert the nested chain into linked Containers.
        Body body = BlockyFactory.eINSTANCE.createBody();
        if (dataList == null || dataList.isEmpty()) {
            body.setFirstContainer(null);
            return body;
        }

        Container first = null;
        Container last = null;

        // If multiple top-level blocks exist, we concatenate their chains in order.
        for (Map<String, Object> data : dataList) {
            Container chainHead = createContainerChainFromData(data);
            if (chainHead == null) continue;

            if (first == null) {
                first = chainHead;
                last = chainHead;
            } else {
                last.setNext(chainHead);
            }

            // Advance last to the end of the appended chain.
            Container cur = chainHead;
            while (cur.getNext() != null) {
                cur = cur.getNext();
            }
            last = cur;
        }

        body.setFirstContainer(first);
        return body;
    }

    private static ConditionKind parseCondition(String type) {
        String lower = type.toLowerCase();
        if (lower.contains("forward") || lower.contains("ahead"))
            return ConditionKind.CHECK_FORWARD;
        if (lower.contains("left"))
            return ConditionKind.CHECK_LEFT;
        if (lower.contains("right"))
            return ConditionKind.CHECK_RIGHT;
        return ConditionKind.CHECK_FORWARD;
    }

    // --- Simulation ---

    public void simulateUserProgram() {
        // Keep existing behavior (stdout + save) for callers that don't need logs.
        simulateUserProgramWithLogs();
    }

    /**
     * Runs the current solution and returns a high-level, step-by-step log.
     * Also saves the model (including execution trace) at the end (same as simulateUserProgram()).
     */
    public List<String> simulateUserProgramWithLogs() {
        List<String> logs = new ArrayList<>();
        System.out.println("\n[GameEngine] Starting simulation...");
        logs.add("Simulation start");

        // Keep Blockly export consistent with MoMoT's BlockySimulator defaults:
        boolean enforceConstraints = Boolean.parseBoolean(System.getProperty("blocky.sim.enforceConstraints", "false"));
                if (enforceConstraints && SimUtils.violatesLevelConstraints(currentLevel, currentLevel.getSolution())) {
            System.out.println("[GameEngine] CRASH: Level constraints violated!");
            logs.add("Result: CRASH (Constraints violated)");
            return logs;
        }

        currentLevel.getTraces().clear();
        ExecutionTrace trace = BlockyFactory.eINSTANCE.createExecutionTrace();
        currentLevel.getTraces().add(trace);

        GameState initialState = BlockyFactory.eINSTANCE.createGameState();
        initialState.setStep(0);

        if (currentLevel == null) {
            logs.add("Result: CRASH (No current level)");
            return logs;
        }

        Cell startNode = null;
        if (currentLevel.getMap() != null) {
            for (Cell c : currentLevel.getMap().getCells()) {
                if (c != null && c.getType() == CellType.START)
                    startNode = c;
            }
            if (startNode == null && !currentLevel.getMap().getCells().isEmpty())
                startNode = currentLevel.getMap().getCells().get(0);
        }

        initialState.setPosition(startNode);
        Direction startDir = SimUtils.determineStartOrientation(currentLevel, startNode);
        initialState.setOrientation(startDir);
        currentLevel.setStartOrientation(startDir);
        initialState.setStatus(GameStatus.RUNNING);
        trace.getStates().add(initialState);

        final CellType winCellType = SimUtils.determineWinCellType(currentLevel);

        System.out.println(
                "[GameEngine] Initial State: Pos=" + getPosStr(startNode) + ", Dir=" + initialState.getOrientation());
        logs.add("Start: " + getPosStr(startNode) + " dir=" + initialState.getOrientation());

        executeBodyWithLogs(currentLevel.getSolution(), initialState, trace, logs, winCellType);
        System.out.println("[GameEngine] Simulation finished.\n");
        GameState last = trace.getStates().isEmpty() ? null : trace.getStates().get(trace.getStates().size() - 1);
        if (last != null) {
            if (last.getStatus() == GameStatus.WON) logs.add("Result: GOAL");
            else if (last.getStatus() == GameStatus.CRASHED) logs.add("Result: CRASH");
            else logs.add("Result: " + last.getStatus());
        }
        return logs;
    }

    private String getPosStr(Cell c) {
        if (c == null)
            return "null";
        return "(" + c.getX() + "," + c.getY() + ")";
    }

    private GameState executeBody(Body body, GameState state, ExecutionTrace trace, CellType winCellType) {
        if (body == null) {
            return state;
        }
        return executeContainerChain(body.getFirstContainer(), state, trace, winCellType);
    }

    private GameState executeBodyWithLogs(Body body, GameState state, ExecutionTrace trace, List<String> logs, CellType winCellType) {
        if (body == null) {
            return state;
        }
        return executeContainerChainWithLogs(body.getFirstContainer(), state, trace, logs, winCellType);
    }

    private GameState executeContainerChain(Container first, GameState state, ExecutionTrace trace, CellType winCellType) {
        Container current = first;
        GameState last = state;
        while (current != null && last.getStatus() == GameStatus.RUNNING) {
            last = executeSingle(current.getStatement(), last, trace, winCellType);
            current = current.getNext();
        }
        return last;
    }

    private GameState executeContainerChainWithLogs(Container first, GameState state, ExecutionTrace trace, List<String> logs, CellType winCellType) {
        Container current = first;
        GameState last = state;
        while (current != null && last.getStatus() == GameStatus.RUNNING) {
            last = executeSingleWithLogs(current.getStatement(), last, trace, logs, winCellType);
            current = current.getNext();
        }
        return last;
    }

    private static SensorDirection conditionKindToSensor(ConditionKind ck) {
        // Align with MoMoT headless simulator defaults: missing condition behaves as CHECK_FORWARD.
        if (ck == null) {
            ck = ConditionKind.CHECK_FORWARD;
        }
        if (ck == ConditionKind.CHECK_LEFT) {
            return SensorDirection.LEFT;
        }
        if (ck == ConditionKind.CHECK_RIGHT) {
            return SensorDirection.RIGHT;
        }
        return SensorDirection.AHEAD;
    }

    private boolean checkCondition(GameState state, ConditionKind ck) {
        return checkSensor(state, conditionKindToSensor(ck));
    }

    private GameState executeSingle(Statement stmt, GameState prev, ExecutionTrace trace, CellType winCellType) {
        GameState next = BlockyFactory.eINSTANCE.createGameState();
        next.setStep(prev.getStep() + 1);
        next.setOrientation(prev.getOrientation());
        next.setPosition(prev.getPosition());
        next.setStatus(GameStatus.RUNNING);
        next.setExecutingStatement(stmt);
        next.setPrevious(prev);
        trace.getStates().add(next);

        if (stmt == null) {
            System.out.print("[GameEngine] Step " + next.getStep() + ": (empty) -> ");
            System.out.println("No-op");
            return next;
        }

        String typeName = stmt.getClass().getSimpleName().replace("Impl", "");
        System.out.print("[GameEngine] Step " + next.getStep() + ": " + typeName + " -> ");

        if (stmt instanceof AtomicStatement) {
            AtomicStatement a = (AtomicStatement) stmt;
            // Align with MoMoT headless simulator defaults: missing kind behaves as TURN_LEFT.
            AtomicStatementKind kind = a.getKind();
            if (kind == null) kind = AtomicStatementKind.TURN_LEFT;
            switch (kind) {
            case MOVE_FORWARD: {
                Cell target = getAdjacent(next.getPosition(), next.getOrientation());
                if (target == null || target.getType() == CellType.WALL) {
                    next.setStatus(GameStatus.CRASHED);
                    System.out.println("CRASHED at " + getPosStr(next.getPosition()));
                } else {
                    next.setPosition(target);
                    System.out.println("Moved to " + getPosStr(target));
                    if (target.getType() == winCellType) {
                        next.setStatus(GameStatus.WON);
                        System.out.println("[GameEngine] SUCCESS: Goal reached!");
                    }
                }
                break;
            }
            case TURN_LEFT:
                next.setOrientation(getRelativeDir(next.getOrientation(), SensorDirection.LEFT));
                System.out.println("TurnLeft. New Dir: " + next.getOrientation());
                break;
            case TURN_RIGHT:
                next.setOrientation(getRelativeDir(next.getOrientation(), SensorDirection.RIGHT));
                System.out.println("TurnRight. New Dir: " + next.getOrientation());
                break;
            default:
                break;
            }
        } else if (stmt instanceof Loop) {
            System.out.println("Loop Start");
            Loop r = (Loop) stmt;
            GameState loop = next;
            GridMap map = currentLevel.getMap();
            int maxSteps = map.getWidth() * map.getHeight() * 2;
            while (loop.getStatus() == GameStatus.RUNNING && loop.getPosition().getType() != winCellType) {
                if (loop.getStep() > maxSteps) {
                    loop.setStatus(GameStatus.CRASHED);
                    System.out.println("[GameEngine] Infinite loop detected! (exceeded " + maxSteps + " steps)");
                    break;
                }
                int previousStep = loop.getStep();
                loop = executeBody(r.getBody(), loop, trace, winCellType);
                if (loop.getStep() == previousStep) {
                    loop.setStatus(GameStatus.CRASHED);
                    System.out.println("[GameEngine] Empty loop body or zero progress in loop! Crashing.");
                    break;
                }
            }
            return loop;
        } else if (stmt instanceof IfStmt) {
            IfStmt i = (IfStmt) stmt;
            boolean cond = checkCondition(next, i.getCondition());
            System.out.println("If (" + conditionKindToSensor(i.getCondition()) + ") is " + cond);
            if (cond) {
                return executeBody(i.getThenBody(), next, trace, winCellType);
            }
            if (i.getElseBody() != null) {
                return executeBody(i.getElseBody(), next, trace, winCellType);
            }
        }
        return next;
    }

    private GameState executeSingleWithLogs(Statement stmt, GameState prev, ExecutionTrace trace, List<String> logs, CellType winCellType) {
        GameState next = BlockyFactory.eINSTANCE.createGameState();
        next.setStep(prev.getStep() + 1);
        next.setOrientation(prev.getOrientation());
        next.setPosition(prev.getPosition());
        next.setStatus(GameStatus.RUNNING);
        next.setExecutingStatement(stmt);
        next.setPrevious(prev);
        trace.getStates().add(next);

        String typeName = stmt != null ? stmt.getClass().getSimpleName().replace("Impl", "") : "null";

        if (stmt == null) {
            if (logs != null) logs.add("Step " + next.getStep() + ": (empty) -> no-op");
            return next;
        }

        if (stmt instanceof AtomicStatement) {
            AtomicStatement a = (AtomicStatement) stmt;
            // Align with MoMoT headless simulator defaults: missing kind behaves as TURN_LEFT.
            AtomicStatementKind kind = a.getKind();
            if (kind == null) kind = AtomicStatementKind.TURN_LEFT;
            switch (kind) {
            case MOVE_FORWARD: {
                Cell target = getAdjacent(next.getPosition(), next.getOrientation());
                if (target == null || target.getType() == CellType.WALL) {
                    next.setStatus(GameStatus.CRASHED);
                    if (logs != null) logs.add("Step " + next.getStep() + ": MoveForward -> CRASH at " + getPosStr(next.getPosition()));
                } else {
                    next.setPosition(target);
                    if (target.getType() == winCellType) {
                        next.setStatus(GameStatus.WON);
                        if (logs != null) logs.add("Step " + next.getStep() + ": MoveForward -> " + getPosStr(target) + " GOAL");
                    } else {
                        if (logs != null) logs.add("Step " + next.getStep() + ": MoveForward -> " + getPosStr(target));
                    }
                }
                break;
            }
            case TURN_LEFT: {
                Direction before = next.getOrientation();
                next.setOrientation(getRelativeDir(next.getOrientation(), SensorDirection.LEFT));
                if (logs != null) logs.add("Step " + next.getStep() + ": TurnLeft -> " + before + "→" + next.getOrientation());
                break;
            }
            case TURN_RIGHT: {
                Direction before = next.getOrientation();
                next.setOrientation(getRelativeDir(next.getOrientation(), SensorDirection.RIGHT));
                if (logs != null) logs.add("Step " + next.getStep() + ": TurnRight -> " + before + "→" + next.getOrientation());
                break;
            }
            default:
                if (logs != null) logs.add("Step " + next.getStep() + ": " + typeName);
                break;
            }
        } else if (stmt instanceof Loop) {
            if (logs != null) logs.add("Step " + next.getStep() + ": Loop");
            Loop r = (Loop) stmt;
            GameState loop = next;
            GridMap map = currentLevel.getMap();
            int maxSteps = map.getWidth() * map.getHeight() * 2;
            while (loop.getStatus() == GameStatus.RUNNING && loop.getPosition().getType() != winCellType) {
                if (loop.getStep() > maxSteps) {
                    loop.setStatus(GameStatus.CRASHED);
                    if (logs != null) logs.add("Result: INFINITE_LOOP (bound " + maxSteps + " exceeded)");
                    break;
                }
                int previousStep = loop.getStep();
                loop = executeBodyWithLogs(r.getBody(), loop, trace, logs, winCellType);
                if (loop.getStep() == previousStep) {
                    loop.setStatus(GameStatus.CRASHED);
                    if (logs != null) logs.add("Result: CRASH (empty loop body / no progress)");
                    break;
                }
            }
            return loop;
        } else if (stmt instanceof IfStmt) {
            IfStmt i = (IfStmt) stmt;
            boolean cond = checkCondition(next, i.getCondition());
            if (logs != null) {
                String branch = cond ? "then" : (i.getElseBody() != null ? "else" : "skip");
                logs.add("Step " + next.getStep() + ": If " + conditionKindToSensor(i.getCondition()) + " -> " + cond + " (" + branch + ")");
            }
            if (cond) {
                return executeBodyWithLogs(i.getThenBody(), next, trace, logs, winCellType);
            }
            if (i.getElseBody() != null) {
                return executeBodyWithLogs(i.getElseBody(), next, trace, logs, winCellType);
            }
        } else {
            if (logs != null) logs.add("Step " + next.getStep() + ": " + typeName);
        }

        return next;
    }

    private boolean checkSensor(GameState state, SensorDirection sensor) {
        Direction actual = getRelativeDir(state.getOrientation(), sensor);
        Cell target = getAdjacent(state.getPosition(), actual);
        return target != null && target.getType() != CellType.WALL;
    }

    private Cell getAdjacent(Cell c, Direction d) {
        if (c == null || d == null) return null;
        switch (d) {
            case NORTH:
                return c.getTop();
            case SOUTH:
                return c.getBottom();
            case EAST:
                return c.getRight();
            case WEST:
                return c.getLeft();
        }
        return null;
    }

    private Direction getRelativeDir(Direction curr, SensorDirection sensor) {
        if (sensor == SensorDirection.AHEAD)
            return curr;
        if (sensor == SensorDirection.LEFT) {
            switch (curr) {
                case NORTH:
                    return Direction.WEST;
                case WEST:
                    return Direction.SOUTH;
                case SOUTH:
                    return Direction.EAST;
                case EAST:
                    return Direction.NORTH;
            }
        } else {
            switch (curr) {
                case NORTH:
                    return Direction.EAST;
                case EAST:
                    return Direction.SOUTH;
                case SOUTH:
                    return Direction.WEST;
                case WEST:
                    return Direction.NORTH;
            }
        }
        return curr;
    }

    // --- Level Metadata Sync ---

    /**
     * Called from the JS bridge when the WebView reports level metadata.
     * JSON shape: { "level": K, "maxBlocks": Od, "startDirection": T,
     *               "allowLoops": bool, "allowConditionals": bool }
     * T encoding: 0=NORTH, 1=EAST, 2=SOUTH, 3=WEST (Blockly Maze always resets to T=1=EAST).
     */
    public void syncLevelMeta(String metaJson) {
        System.out.println("[GameEngine] Syncing level metadata: " + metaJson);
        try {
            int maxBlocks        = extractJsonInt(metaJson, "maxBlocks",        -1);
            int startDirCode     = extractJsonInt(metaJson, "startDirection",    1);
            int levelNum         = extractJsonInt(metaJson, "level",             1);
            boolean allowLoops   = extractJsonBool(metaJson, "allowLoops",   false);
            boolean allowConds   = extractJsonBool(metaJson, "allowConditionals", false);
            boolean allowIfElse  = extractJsonBool(metaJson, "allowIfElse", false);

            currentLevel.setId(levelNum);
            currentLevel.setTitle("Maze Level " + levelNum);
            currentLevel.setMaxBlocks(maxBlocks < 0 ? 0 : maxBlocks);
            currentLevel.setAllowLoops(allowLoops);
            currentLevel.setAllowConditionals(allowConds);
            currentLevel.setAllowIfElse(allowIfElse);

            // Blockly's startDirection is frequently unreliable (often defaulting to EAST).
            // Prefer an explicitly stored model value; otherwise derive it from the map layout.
            Direction dir = null;
            if (currentLevel != null && currentLevel.eIsSet(BlockyPackage.Literals.LEVEL__START_ORIENTATION)) {
                dir = currentLevel.getStartOrientation();
            } else if (currentLevel != null && currentLevel.getMap() != null) {
                Cell startCell = getStartCell(currentLevel.getMap());
                dir = SimUtils.determineStartOrientation(currentLevel, startCell);
            }
            if (dir == null) {
                switch (startDirCode) {
                    case 0:  dir = Direction.NORTH; break;
                    case 1:  dir = Direction.EAST;  break;
                    case 2:  dir = Direction.SOUTH; break;
                    case 3:  dir = Direction.WEST;  break;
                    default: dir = Direction.EAST;
                }
            }
            if (!debugSessionActive) {
                currentLevel.setStartOrientation(dir);
            } else {
                System.out.println("[GameEngine] Ignoring startDirection update during active debug session.");
            }

            System.out.println("[GameEngine] Level=" + levelNum
                    + ", maxBlocks=" + (maxBlocks < 0 ? "unlimited" : maxBlocks)
                    + ", startDir=" + dir
                    + ", allowLoops=" + allowLoops
                    + ", allowConditionals=" + allowConds);
        } catch (Exception e) {
            System.err.println("[GameEngine] Failed to parse level metadata: " + e.getMessage());
        }
    }

    private int extractJsonInt(String json, String key, int defaultVal) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?[0-9]+)")
                .matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : defaultVal;
    }

    private boolean extractJsonBool(String json, String key, boolean defaultVal) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(true|false)")
                .matcher(json);
        return m.find() ? Boolean.parseBoolean(m.group(1)) : defaultVal;
    }

    public void saveToFile(File file) {
        if (file == null) return;
        try {
            // Ensure XMI always contains a non-null solution container to avoid downstream tooling issues.
            ensureLevelHasNonNullSolution(currentLevel);
            resource.setURI(URI.createFileURI(file.getAbsolutePath()));
            resource.save(null);
            System.out.println("[GameEngine] Model saved to: " + resource.getURI().toFileString());
        } catch (Exception e) {
            System.err.println("[GameEngine] SAVE FAILED to " + file.getAbsolutePath() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public Level getCurrentLevel() {
        return currentLevel;
    }

    public Game getCurrentGame() {
        return currentGame;
    }

    public Resource getResource() {
        return resource;
    }

    // --- Immediate Feedback getters (old vs new path) ---
    public int[][] getPastPath() {
        return pastPath != null ? pastPath : new int[0][0];
    }

    public int[][] getNewPath() {
        return newPath != null ? newPath : new int[0][0];
    }

    // --- XMI Load & Export for WebView ---

    /**
     * Loads a model from an XMI file and sets the current game/level.
     * Subsequent saveModel() will write to this file.
     *
     * Accepts both:
     * - Game root (new model)
     * - Level root (legacy) — will be wrapped into a Game root in-memory
     *
     * @param file the .xmi file to load
     * @throws IOException if the file cannot be read or parsed
     * @throws IllegalArgumentException if the root element is not compatible or level has no map
     */
    public void loadFromFile(File file) throws IOException {
        if (file == null || !file.exists()) {
            throw new IOException("File does not exist: " + (file == null ? "null" : file.getAbsolutePath()));
        }
        BlockyPackage.eINSTANCE.eClass();
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl());

        ResourceSet resSet = new ResourceSetImpl();
        URI uri = URI.createFileURI(file.getAbsolutePath());
        Resource newResource = resSet.createResource(uri);
        newResource.load(null);

        if (newResource.getContents().isEmpty()) {
            throw new IllegalArgumentException("XMI file has no root element: " + file.getAbsolutePath());
        }
        Object root = newResource.getContents().get(0);

        Game loadedGame;
        Level loadedLevel;

        if (root instanceof Game) {
            loadedGame = (Game) root;
            if (loadedGame.getLevels().isEmpty()) {
                throw new IllegalArgumentException("Game has no levels.");
            }
            loadedLevel = loadedGame.getLevels().get(0);
        } else if (root instanceof Level) {
            loadedLevel = (Level) root;
            loadedGame = BlockyFactory.eINSTANCE.createGame();
            loadedGame.getLevels().add(loadedLevel);
            newResource.getContents().clear();
            newResource.getContents().add(loadedGame);
        } else {
            throw new IllegalArgumentException("XMI root is neither Game nor Level: "
                    + (root != null ? root.getClass().getName() : "null"));
        }

        if (loadedLevel.getMap() == null) {
            throw new IllegalArgumentException("Level has no map.");
        }
        this.resource = newResource;
        this.currentGame = loadedGame;
        this.currentLevel = loadedLevel;

        // If the XMI doesn't explicitly store a start orientation, derive a stable one from the map.
        // This prevents stale/default WebView metadata (often EAST) from "winning" after a model load.
        if (this.currentLevel != null
                && !this.currentLevel.eIsSet(BlockyPackage.Literals.LEVEL__START_ORIENTATION)
                && this.currentLevel.getMap() != null) {
            Cell startCell = getStartCell(this.currentLevel.getMap());
            Direction derived = SimUtils.determineStartOrientation(this.currentLevel, startCell);
            this.currentLevel.setStartOrientation(derived);
        }
        try {
            // Compute old (stored) vs new (re-simulated) paths for immediate feedback.
            ImmediateFeedbackService.Paths paths = ImmediateFeedbackService.computePaths(loadedLevel);
            this.pastPath = paths.pastPath;
            this.newPath = paths.newPath;
        } catch (Exception e) {
            System.err.println("[GameEngine] ImmediateFeedback compute failed: " + e.getMessage());
            this.pastPath = new int[0][0];
            this.newPath = new int[0][0];
        }

        // If the next load is a MoMoT solution (armed by UI), compute DM path diff overlays.
        if (dmCompareArmed && dmBaselinePath != null && dmBaselinePath.length > 0) {
            try {
                blocky_game.DebuggingService.DebugTraceResult sol =
                        blocky_game.DebuggingService.computeTraceFromState(loadedLevel, dmStartX, dmStartY, dmStartDir);
                dmSolutionPath = compressTracePositions(sol.trace, sol.trace != null && sol.trace.getStates() != null ? sol.trace.getStates().size() - 1 : 0);
                dmCommonLen = longestCommonPrefixLenByXY(dmBaselinePath, dmSolutionPath);
                dmAlignedValid = false;
                if (dmCommonLen > 0 && dmBaselinePath.length >= dmCommonLen) {
                    // Walk back from the last common cell to find the first RUNNING state.
                    // This avoids aligning onto a WON/CRASHED terminal state if the paths are common up to the goal.
                    for (int i = dmCommonLen - 1; i >= 0; i--) {
                        int[] pt = dmBaselinePath[i];
                        if (pt != null && pt.length >= 2) {
                            int tx = pt[0];
                            int ty = pt[1];
                            int idx = findLastRunningStateIndexAtOrBefore(sol.trace, tx, ty);
                            if (idx >= 0) {
                                dmAlignedX = tx;
                                dmAlignedY = ty;
                                dmAlignedStateIndex = idx;
                                dmAlignedValid = true;
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                dmSolutionPath = new int[0][0];
                dmCommonLen = 0;
                dmAlignedValid = false;
            } finally {
                dmCompareArmed = false;
            }
        } else {
            dmCompareArmed = false;
        }

        System.out.println("[GameEngine] Loaded level id=" + loadedLevel.getId() + " from " + file.getAbsolutePath());
    }

    /** Arm DM comparison overlays for the next model load (used when loading a MoMoT result). */
    public void armDirectManipulationComparison() {
        this.dmCompareArmed = true;
    }

    public boolean hasDirectManipulationComparison() {
        return dmSolutionPath != null && dmSolutionPath.length > 0 && dmBaselinePath != null && dmBaselinePath.length > 0;
    }

    public int[][] getDmBaselinePath() {
        return dmBaselinePath != null ? dmBaselinePath : new int[0][0];
    }

    public int[][] getDmSolutionPath() {
        return dmSolutionPath != null ? dmSolutionPath : new int[0][0];
    }

    public int getDmCommonLen() {
        return dmCommonLen;
    }

    public boolean isDmAlignedValid() {
        return dmAlignedValid;
    }

    public int getDmAlignedX() {
        return dmAlignedX;
    }

    public int getDmAlignedY() {
        return dmAlignedY;
    }

    // --- Debugger Controls (Java-driven stepping) ---

    /**
     * Start a debug session from a specific WebView pegman state.
     * This computes a full trace and resets debugIndex to 0.
     *
     * @param startX cell x (WebView "Q")
     * @param startY cell y (WebView "S")
     * @param startT direction code (WebView "T": 0=N,1=E,2=S,3=W)
     * @return JSON frame describing current debug state for the UI
     */
    public String debugStart(int startX, int startY, int startT) {
        if (currentLevel == null) {
            return "{\"index\":0,\"total\":0}";
        }
        debugStartX = startX;
        debugStartY = startY;
        debugStartDir = blocky_game.DebuggingService.tToDirection(startT);
        debugCurrentX = startX;
        debugCurrentY = startY;
        debugCurrentDir = debugStartDir;
        debugPaused = true;
        debugDirtySolution = false;
        debugSessionActive = true;

        // Special-case: after loading a MoMoT solution, the UI teleports pegman to the last common cell
        // between baseline and solution paths. In that case, starting execution from statement #1 would
        // incorrectly re-run already-executed prefix commands and can fail to reach the goal.
        //
        // Fix: build the trace from the REAL comparison start (dmStartX/Y/Dir) and jump debugIndex
        // to the last state that reaches the last-common cell. This preserves correct "next block".
        int desiredIndex = -1;
        int traceStartX = startX;
        int traceStartY = startY;
        Direction traceStartDir = debugStartDir;
        try {
            if (hasDirectManipulationComparison() && dmAlignedValid && startX == dmAlignedX && startY == dmAlignedY) {
                traceStartX = dmStartX;
                traceStartY = dmStartY;
                traceStartDir = dmStartDir != null ? dmStartDir : debugStartDir;
                desiredIndex = dmAlignedStateIndex;
            }
        } catch (Exception ignored) {
        }

        blocky_game.DebuggingService.DebugTraceResult result =
                blocky_game.DebuggingService.computeTraceFromState(currentLevel, traceStartX, traceStartY, traceStartDir);
        debugTrace = result.trace;
        debugLogLines = result.logLines;

        try {
            if (debugTrace != null && debugTrace.getStates() != null) {
                // If we computed the trace from a different start than the UI's desired (q,s),
                // jump the index to the state that reaches the UI cell.
                if (traceStartX != startX || traceStartY != startY) {
                    if (desiredIndex < 0) {
                        desiredIndex = findLastRunningStateIndexAtOrBefore(debugTrace, startX, startY);
                    }
                    // Sanity-check: ensure the chosen index actually matches the requested cell.
                    int actualX = -1, actualY = -1;
                    try {
                        GameState as = debugTrace.getStates().get(Math.max(0, Math.min(desiredIndex, debugTrace.getStates().size() - 1)));
                        Cell ap = as != null ? as.getPosition() : null;
                        if (ap != null) { actualX = ap.getX(); actualY = ap.getY(); }
                    } catch (Exception ignored2) {}
                    System.out.println("[GameEngine] debugStart aligned: traceStart=(" + traceStartX + "," + traceStartY + ") -> ui=("
                            + startX + "," + startY + ") mappedIndex=" + desiredIndex + " actual=(" + actualX + "," + actualY + ")");
                    if (desiredIndex < 0) {
                        // If the requested alignment cell is not on the new trace, do NOT snap back to the start.
                        // Fall back to a trace starting from the requested cell so stepping remains usable.
                        blocky_game.DebuggingService.DebugTraceResult fallback =
                                blocky_game.DebuggingService.computeTraceFromState(currentLevel, startX, startY, debugStartDir);
                        debugTrace = fallback.trace;
                        debugLogLines = fallback.logLines;
                        desiredIndex = 0;
                        debugImmediateFeedbackNote = "Debugger: alignment cell (" + startX + "," + startY
                                + ") not found on recomputed trace; starting from that cell.";
                    }
                }
            }
        } catch (Exception ignored) {
            desiredIndex = 0;
        }

        debugIndex = Math.max(0, Math.min(desiredIndex, (debugTrace != null && debugTrace.getStates() != null) ? debugTrace.getStates().size() - 1 : 0));
        syncCurrentStateSnapshotFromTraceIndex();
        debugPastPrefixPath = new int[0][0];
        debugImmediateFeedbackNote = null;

        // Return frame for UI rendering.
        return debugFrameJson();
    }

    public String debugTogglePause() {
        if (debugTrace == null) {
            // Not started yet; UI should call debugStart first.
            return debugFrameJson();
        }
        debugPaused = !debugPaused;
        if (!debugPaused && debugDirtySolution) {
            // User resumes after editing while paused: perform Immediate Feedback alignment.
            alignDebugTraceAfterPausedEdits(/*advanceOneStepAfterAlign*/ false);
            debugDirtySolution = false;
        }
        return debugFrameJson();
    }

    public String debugStepOnce() {
        if (currentLevel == null) return "{\"index\":0,\"total\":0}";
        if (debugTrace == null) {
            // Can't step without a trace; UI should have called debugStart.
            return debugFrameJson();
        }

        if (debugDirtySolution) {
            // User steps after editing while paused: align to last common point, then step one.
            alignDebugTraceAfterPausedEdits(/*advanceOneStepAfterAlign*/ true);
            debugDirtySolution = false;
            debugPaused = true;
            syncCurrentStateSnapshotFromTraceIndex();
            return debugFrameJson();
        }

        int lastIndex = debugTrace.getStates().size() - 1;
        if (debugIndex < lastIndex) {
            debugIndex++;
        }
        debugPaused = true;
        syncCurrentStateSnapshotFromTraceIndex();
        return debugFrameJson();
    }

    public String debugStop() {
        if (currentLevel == null) return "{\"index\":0,\"total\":0}";
        if (debugTrace == null) return debugFrameJson();

        debugPaused = true;
        debugCurrentX = debugStartX;
        debugCurrentY = debugStartY;
        debugCurrentDir = debugStartDir;
        debugIndex = 0;

        // If solution changed while paused, recompute immediately so overlays/pegman stay consistent.
        if (debugDirtySolution) {
            recomputeDebugTraceFromCurrentState();
            debugDirtySolution = false;
        }
        debugSessionActive = false;

        return debugFrameJson();
    }

    /**
     * Jump directly to the final debug state and pause there.
     * Useful for quickly reaching GOAL/CRASH/INFINITE_LOOP outcome.
     */
    public String debugSkipToEnd() {
        if (debugTrace == null || debugTrace.getStates() == null || debugTrace.getStates().isEmpty()) {
            return debugFrameJson();
        }
        debugIndex = debugTrace.getStates().size() - 1;
        debugPaused = true;
        syncCurrentStateSnapshotFromTraceIndex();
        return debugFrameJson();
    }

    /**
     * Called periodically while running (Resume pressed).
     * If paused or at end, returns the current frame.
     */
    public String debugTick() {
        if (debugTrace == null) return debugFrameJson();
        if (debugPaused) return debugFrameJson();

        // Advance one state.
        int lastIndex = debugTrace.getStates().size() - 1;
        if (debugIndex < lastIndex) {
            debugIndex++;
            syncCurrentStateSnapshotFromTraceIndex();
        } else {
            // End reached; pause.
            debugPaused = true;
        }

        if (debugDirtySolution) {
            // If user edited blocks while we were running, pause + align to last common point
            // between already navigated prefix and newly predicted path.
            alignDebugTraceAfterPausedEdits(/*advanceOneStepAfterAlign*/ false);
            debugDirtySolution = false;
            debugPaused = true;
        }

        return debugFrameJson();
    }

    /**
     * Immediate Feedback during debugging: when user edits blocks while paused, we:
     * - keep the already navigated prefix path as "past"
     * - recompute the new trace from the original debug start snapshot
     * - find the last common point (path-only match by (x,y))
     * - teleport/align debugger state to that point in the new trace
     * - optionally advance one step (for Step button semantics)
     */
    private void alignDebugTraceAfterPausedEdits(boolean advanceOneStepAfterAlign) {
        if (currentLevel == null) return;
        if (debugTrace == null || debugTrace.getStates() == null || debugTrace.getStates().isEmpty()) return;

        // Past prefix (compressed) from current trace up to current index.
        int[][] pastPrefix = compressTracePositions(debugTrace, debugIndex);
        debugPastPrefixPath = pastPrefix != null ? pastPrefix : new int[0][0];

        // New predicted full trace from the original debug start snapshot with updated solution blocks.
        blocky_game.DebuggingService.DebugTraceResult result =
                blocky_game.DebuggingService.computeTraceFromState(currentLevel, debugStartX, debugStartY, debugStartDir);
        ExecutionTrace newTrace = result.trace;
        if (newTrace == null || newTrace.getStates() == null || newTrace.getStates().isEmpty()) {
            return;
        }

        int[][] newFull = compressTracePositions(newTrace, newTrace.getStates().size() - 1);

        // Find longest common prefix length by (x,y).
        int commonLen = longestCommonPrefixLenByXY(debugPastPrefixPath, newFull);
        // Always keep at least the start point if available.
        if (commonLen <= 0 && newFull != null && newFull.length > 0) {
            commonLen = 1;
        }

        int mappedIndex = -1;
        int commonX = debugStartX;
        int commonY = debugStartY;
        if (commonLen > 0 && newFull != null && newFull.length >= commonLen) {
            // Walk back from the last common cell to find the first RUNNING state.
            for (int i = commonLen - 1; i >= 0; i--) {
                int[] pt = newFull[i];
                if (pt != null && pt.length >= 2) {
                    int idx = findLastRunningStateIndexAtOrBefore(newTrace, pt[0], pt[1]);
                    if (idx >= 0) {
                        commonX = pt[0];
                        commonY = pt[1];
                        mappedIndex = idx;
                        break;
                    }
                }
            }
        }

        if (mappedIndex < 0) {
            mappedIndex = 0;
            commonX = debugStartX;
            commonY = debugStartY;
        }

        debugTrace = newTrace;
        debugLogLines = result.logLines;
        debugIndex = Math.max(0, Math.min(mappedIndex, newTrace.getStates().size() - 1));
        syncCurrentStateSnapshotFromTraceIndex();

        // Emit a one-shot note to the UI log so users can see that a program change caused a realignment.
        debugImmediateFeedbackNote = "Immediate Feedback: program changed -> aligned to last common cell ("
                + commonX + "," + commonY + "), commonPrefixLen=" + commonLen
                + (advanceOneStepAfterAlign ? " (then stepped once)" : "");

        if (advanceOneStepAfterAlign) {
            int lastIndex = debugTrace.getStates().size() - 1;
            if (debugIndex < lastIndex) {
                debugIndex++;
                syncCurrentStateSnapshotFromTraceIndex();
            }
        }
    }

    private static int longestCommonPrefixLenByXY(int[][] a, int[][] b) {
        if (a == null || b == null) return 0;
        int n = Math.min(a.length, b.length);
        int i = 0;
        for (; i < n; i++) {
            int[] pa = a[i];
            int[] pb = b[i];
            if (pa == null || pb == null || pa.length < 2 || pb.length < 2) break;
            if (pa[0] != pb[0] || pa[1] != pb[1]) break;
        }
        return i;
    }

    private static int findLastStateIndexAtOrBefore(ExecutionTrace trace, int x, int y) {
        if (trace == null || trace.getStates() == null) return -1;
        int best = -1;
        List<GameState> states = trace.getStates();
        for (int i = 0; i < states.size(); i++) {
            GameState s = states.get(i);
            Cell pos = s != null ? s.getPosition() : null;
            if (pos == null) continue;
            if (pos.getX() == x && pos.getY() == y) {
                best = i;
            }
        }
        return best;
    }

    /**
     * Finds the last state index that is at (x,y) and still RUNNING.
     * This is preferred for "continue from here" alignment so we don't align onto a CRASH/WON terminal frame.
     */
    private static int findLastRunningStateIndexAtOrBefore(ExecutionTrace trace, int x, int y) {
        if (trace == null || trace.getStates() == null) return -1;
        int best = -1;
        List<GameState> states = trace.getStates();
        for (int i = 0; i < states.size(); i++) {
            GameState s = states.get(i);
            if (s == null) continue;
            Cell pos = s.getPosition();
            if (pos == null) continue;
            if (pos.getX() == x && pos.getY() == y && s.getStatus() == GameStatus.RUNNING) {
                best = i;
            }
        }
        return best;
    }

    /**
     * Compresses a trace into unique (x,y) points up to {@code uptoIndexInclusive}.
     * Turn-only steps are collapsed since they keep the same cell position.
     */
    private static int[][] compressTracePositions(ExecutionTrace trace, int uptoIndexInclusive) {
        if (trace == null || trace.getStates() == null || trace.getStates().isEmpty()) return new int[0][0];
        List<GameState> states = trace.getStates();
        int safeUpto = Math.max(0, Math.min(uptoIndexInclusive, states.size() - 1));
        List<int[]> pts = new ArrayList<>();
        Integer lastX = null;
        Integer lastY = null;
        for (int i = 0; i <= safeUpto; i++) {
            GameState s = states.get(i);
            Cell pos = s != null ? s.getPosition() : null;
            if (pos == null) continue;
            int x = pos.getX();
            int y = pos.getY();
            if (lastX != null && lastX == x && lastY == y) continue;
            pts.add(new int[] { x, y });
            lastX = x;
            lastY = y;
        }
        int[][] arr = new int[pts.size()][2];
        for (int i = 0; i < pts.size(); i++) {
            int[] p = pts.get(i);
            arr[i][0] = p[0];
            arr[i][1] = p[1];
        }
        return arr;
    }

    private void recomputeDebugTraceFromCurrentState() {
        if (currentLevel == null) return;
        Direction dir = debugCurrentDir != null ? debugCurrentDir : Direction.EAST;
        blocky_game.DebuggingService.DebugTraceResult result =
                blocky_game.DebuggingService.computeTraceFromState(currentLevel, debugCurrentX, debugCurrentY, dir);
        debugTrace = result.trace;
        debugLogLines = result.logLines;
        debugIndex = 0;
    }

    private void syncCurrentStateSnapshotFromTraceIndex() {
        if (debugTrace == null || debugTrace.getStates() == null || debugTrace.getStates().isEmpty()) return;
        int safeIndex = Math.max(0, Math.min(debugIndex, debugTrace.getStates().size() - 1));
        GameState s = debugTrace.getStates().get(safeIndex);
        Cell pos = s != null ? s.getPosition() : null;
        if (pos != null) {
            debugCurrentX = pos.getX();
            debugCurrentY = pos.getY();
        }
        debugCurrentDir = s != null ? s.getOrientation() : debugCurrentDir;
    }

    private String debugFrameJson() {
        // Always return a full frame shape so the WebView doesn't interpret missing fields as 0,0.
        // This situation can happen transiently during level/model injection (e.g. MoMoT in-place load)
        // when debug controls are clicked while trace/state is not yet initialized.
        if (currentLevel == null || debugTrace == null || debugTrace.getStates() == null) {
            int q = debugCurrentX;
            int r = debugCurrentY;
            int t = blocky_game.DebuggingService.directionToT(debugCurrentDir);
            try {
                if (currentLevel != null && currentLevel.getMap() != null) {
                    Cell start = getStartCell(currentLevel.getMap());
                    if (start != null) {
                        q = start.getX();
                        r = start.getY();
                    }
                    Direction dir = (currentLevel != null && currentLevel.eIsSet(BlockyPackage.Literals.LEVEL__START_ORIENTATION))
                            ? currentLevel.getStartOrientation()
                            : null;
                    if (dir != null) {
                        t = blocky_game.DebuggingService.directionToT(dir);
                    }
                }
            } catch (Exception ignored) {
            }
            return "{\"index\":0"
                    + ",\"total\":0"
                    + ",\"q\":" + q
                    + ",\"s\":" + r
                    + ",\"t\":" + t
                    + ",\"prefix\":[]"
                    + ",\"pastPrefix\":[]"
                    + ",\"newPreview\":[]"
                    + ",\"common\":0"
                    + ",\"paused\":true"
                    + ",\"dirty\":false"
                    + ",\"blockId\":\"\""
                    + ",\"result\":\"RUNNING\""
                    + ",\"logLine\":\"\""
                    + ",\"note\":\"\""
                    + "}";
        }
        List<GameState> states = debugTrace.getStates();
        int total = states.size();
        int safeIndex = Math.max(0, Math.min(debugIndex, total - 1));
        GameState s = states.get(safeIndex);
        Cell pos = s != null ? s.getPosition() : null;
        Direction dir = s != null ? s.getOrientation() : debugCurrentDir;
        int q = pos != null ? pos.getX() : debugCurrentX;
        int r = pos != null ? pos.getY() : debugCurrentY;
        int t = blocky_game.DebuggingService.directionToT(dir);

        // Prefix path up to current index (compressed).
        int[][] prefixPathArr = compressTracePositions(debugTrace, safeIndex);
        int prefixLen = prefixPathArr.length;
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < prefixLen; i++) {
            if (i > 0) sb.append(",");
            sb.append("[").append(prefixPathArr[i][0]).append(",").append(prefixPathArr[i][1]).append("]");
        }
        sb.append("]");

        // Immediate Feedback for paused edits: past prefix vs newly predicted preview (path-only).
        int[][] pastPrefixPath = (debugPastPrefixPath != null) ? debugPastPrefixPath : new int[0][0];
        // newPreview should be the full predicted path from the (possibly updated) program.
        int[][] newPreviewPath = compressTracePositions(debugTrace, debugTrace.getStates().size() - 1);
        
        // The orange path should ONLY show what is ahead of the current index.
        // Since both paths are now compressed, prefixLen correctly points to the index 
        // in newPreviewPath that corresponds to the pegman's current cell.
        int commonLen = prefixLen;

        String result = "RUNNING";
        if (safeIndex >= total - 1) {
            GameStatus st = s != null ? s.getStatus() : null;
            if (st == GameStatus.WON) {
                result = "GOAL";
            } else if (st == GameStatus.CRASHED) {
                // Heuristic: crashes near/above loop bound are typically infinite loops.
                int loopBound = 0;
                if (currentLevel != null && currentLevel.getMap() != null) {
                    loopBound = currentLevel.getMap().getWidth() * currentLevel.getMap().getHeight() * 2;
                }
                result = (s != null && loopBound > 0 && s.getStep() >= loopBound) ? "INFINITE_LOOP" : "CRASH";
            }
        }

        String logLine = null;
        if (debugLogLines != null && safeIndex >= 0 && safeIndex < debugLogLines.size()) {
            logLine = debugLogLines.get(safeIndex);
        }
        if (logLine == null) {
            logLine = "Step " + (s != null ? s.getStep() : safeIndex) + ": (no log)";
        }
        logLine = escapeJsonString(logLine);

        // Provide a prefix of log lines up to the current index so the UI can render
        // a complete execution history even when we start debugging from an aligned mid-trace index.
        String logPrefix = "[]";
        String logFromAlign = "[]";
        try {
            if (debugLogLines != null && !debugLogLines.isEmpty()) {
                int upto = Math.max(0, Math.min(safeIndex, debugLogLines.size() - 1));
                StringBuilder lp = new StringBuilder();
                lp.append("[");
                for (int i = 0; i <= upto; i++) {
                    if (i > 0) lp.append(",");
                    lp.append("\"").append(escapeJsonString(debugLogLines.get(i))).append("\"");
                }
                lp.append("]");
                logPrefix = lp.toString();

                // If we have a MoMoT/DM alignment point, also provide a slice from that alignment index.
                if (dmAlignedValid && dmAlignedStateIndex >= 0 && dmAlignedStateIndex <= upto) {
                    StringBuilder la = new StringBuilder();
                    la.append("[");
                    for (int i = dmAlignedStateIndex; i <= upto; i++) {
                        if (i > dmAlignedStateIndex) la.append(",");
                        la.append("\"").append(escapeJsonString(debugLogLines.get(i))).append("\"");
                    }
                    la.append("]");
                    logFromAlign = la.toString();
                }
            }
        } catch (Exception ignored) {
            logPrefix = "[]";
            logFromAlign = "[]";
        }

        String blockId = "";
        try {
            // Highlight the NEXT statement to be executed (Blockly Maze semantics),
            // while pegman position is the CURRENT state.
            Statement exec = null;
            if (states != null && safeIndex < total - 1) {
                GameState nextState = states.get(Math.min(safeIndex + 1, total - 1));
                exec = nextState != null ? nextState.getExecutingStatement() : null;
            } else {
                exec = (s != null) ? s.getExecutingStatement() : null;
            }
            String mapped = (exec != null) ? stmtToBlocklyId.get(exec) : null;
            blockId = mapped != null ? mapped : "";
        } catch (Exception ignored) {
            blockId = "";
        }
        blockId = escapeJsonString(blockId);

        // notes are one-shot: return once, then clear so the UI won't append duplicates
        String note = debugImmediateFeedbackNote != null ? debugImmediateFeedbackNote : "";
        if (note != null && !note.isEmpty()) {
            debugImmediateFeedbackNote = null;
        }
        note = escapeJsonString(note);

        return "{\"index\":" + safeIndex
                + ",\"total\":" + total
                + ",\"q\":" + q
                + ",\"s\":" + r
                + ",\"t\":" + t
                + ",\"prefix\":" + sb.toString()
                + ",\"pastPrefix\":" + ImmediateFeedbackService.toJsonArray(pastPrefixPath)
                + ",\"newPreview\":" + ImmediateFeedbackService.toJsonArray(newPreviewPath)
                + ",\"common\":" + commonLen
                + ",\"paused\":" + debugPaused
                + ",\"dirty\":" + debugDirtySolution
                + ",\"blockId\":\"" + blockId + "\""
                + ",\"result\":\"" + result + "\""
                + ",\"logLine\":\"" + logLine + "\""
                + ",\"logPrefix\":" + logPrefix
                + ",\"logFromAlign\":" + logFromAlign
                + ",\"note\":\"" + note + "\""
                + "}";
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    /**
     * Converts a linked list of {@link Container}s to Blockly XML (without outer &lt;xml&gt;).
     */
    public String statementChainToXml(Container first) {
        if (first == null) return "";
        StringBuilder sb = new StringBuilder();
        appendStatementXml(first, null, sb);
        return sb.toString();
    }

    /**
     * Returns full Blockly XML document for the level's solution (with &lt;xml&gt; wrapper).
     * If solution is null, returns &lt;xml&gt;&lt;/xml&gt;.
     */
    public String solutionToBlocklyXml(Level level) {
        if (level == null || level.getSolution() == null) {
            return "<xml></xml>";
        }
        Container first = level.getSolution().getFirstContainer();
        if (first == null) return "<xml></xml>";

        String inner = statementChainToXml(first);
        
        String xml = "<xml xmlns=\"https://developers.google.com/blockly/xml\">" + inner + "</xml>";
        // Ensure the first block has x,y coordinates for Blockly's workspace loader.
        if (xml.contains("<block ")) {
            xml = xml.replaceFirst("<block ", "<block x=\"70\" y=\"70\" ");
        }

        System.out.println("[GameEngine] solutionToBlocklyXml: " + xml);
        return xml;
    }

    /**
     * Appends Blockly XML for a container chain.
     *
     * @param c            current container
     * @param overrideNext if non-null, use this as the "next" container instead of {@code c.getNext()}.
     *                     This is used to "inline" non-representable constructs (e.g. nested loops) while
     *                     still preserving the overall statement sequence.
     */
    private void appendStatementXml(Container c, Container overrideNext, StringBuilder sb) {
        if (c == null) return;
        Statement stmt = c.getStatement();
        Container next = overrideNext != null ? overrideNext : c.getNext();
        if (stmt == null) {
            // Empty container: skip but preserve the rest of the chain.
            if (next != null) {
                appendStatementXml(next, null, sb);
            }
            return;
        }

        if (stmt instanceof AtomicStatement) {
            AtomicStatement a = (AtomicStatement) stmt;
            // Keep Blockly export consistent with MoMoT's BlockySimulator defaults:
            // missing kind behaves as TURN_LEFT.
            AtomicStatementKind kind = a.getKind();
            if (kind == null) {
                kind = AtomicStatementKind.TURN_LEFT;
            }
            if (kind.getValue() == AtomicStatementKind.MOVE_FORWARD_VALUE) {
                sb.append("<block type=\"maze_moveForward\">");
            } else if (kind.getValue() == AtomicStatementKind.TURN_LEFT_VALUE) {
                sb.append("<block type=\"maze_turn\"><field name=\"DIR\">turnLeft</field>");
            } else if (kind.getValue() == AtomicStatementKind.TURN_RIGHT_VALUE) {
                sb.append("<block type=\"maze_turn\"><field name=\"DIR\">turnRight</field>");
            } else {
                // Fallback for unknown kinds to avoid empty blocks.
                sb.append("<block type=\"maze_moveForward\">");
            }

            if (next != null) {
                sb.append("<next>");
                appendStatementXml(next, null, sb);
                sb.append("</next>");
            }
            sb.append("</block>");
        } else if (stmt instanceof Loop) {
            Loop r = (Loop) stmt;
            // Produced as a "Repeat Until Goal" block.
            // Note: In Blockly Maze, this block is TERMINAL and does not support a <next> connection.
            sb.append("<block type=\"maze_forever\">");
            sb.append("<statement name=\"DO\">");
            if (r.getBody() != null) {
                appendStatementXml(r.getBody().getFirstContainer(), null, sb);
            }
            sb.append("</statement>");
            sb.append("</block>");
        } else if (stmt instanceof IfStmt) {
            IfStmt i = (IfStmt) stmt;
            // Keep Blockly export consistent with MoMoT's BlockySimulator defaults:
            // missing condition behaves as CHECK_FORWARD.
            ConditionKind ck = i.getCondition();
            if (ck == null) {
                ck = ConditionKind.CHECK_FORWARD;
            }
            String dirField = conditionToBlocklyDir(ck);
            // Blockly Maze's if-else block expects an ELSE statement input to exist.
            // Treat an empty else-body as "no else" to keep the XML loadable.
            boolean hasElse = i.getElseBody() != null && i.getElseBody().getFirstContainer() != null;
            String blockType = hasElse ? "maze_ifElse" : "maze_if";
            sb.append("<block type=\"").append(blockType).append("\">");
            sb.append("<field name=\"DIR\">").append(escapeXml(dirField)).append("</field>");
            sb.append("<statement name=\"DO\">");
            if (i.getThenBody() != null) {
                appendStatementXml(i.getThenBody().getFirstContainer(), null, sb);
            }
            sb.append("</statement>");
            if (hasElse) {
                sb.append("<statement name=\"ELSE\">");
                appendStatementXml(i.getElseBody().getFirstContainer(), null, sb);
                sb.append("</statement>");
            }
            if (next != null) {
                sb.append("<next>");
                appendStatementXml(next, null, sb);
                sb.append("</next>");
            }
            sb.append("</block>");
        } else {
            if (next != null) {
                appendStatementXml(next, null, sb);
            }
        }
    }

    private static String conditionToBlocklyDir(ConditionKind ck) {
        if (ck == ConditionKind.CHECK_LEFT) return "isPathLeft";
        if (ck == ConditionKind.CHECK_RIGHT) return "isPathRight";
        return "isPathForward";
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    /**
     * Builds the 2D grid for the WebView: X[row][col], values 0=WALL, 1=EMPTY, 2=START, 3=GOAL.
     * Dimensions are [height][width] to match JS X[row][col].
     */
    public int[][] buildGridForWebView(GridMap map) {
        if (map == null) return new int[0][0];
        int w = map.getWidth();
        int h = map.getHeight();
        int[][] grid = new int[h][w];

        // Determine which cell type behaves as the "goal" (value 3) in the WebView.
        // We must align this with BlockySimulator/SimUtils.determineWinCellType.
        Cell dmgCell = getDmgCell(map);
        boolean hasDmg = dmgCell != null;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                grid[y][x] = 0; // default WALL
            }
        }
        for (Cell c : map.getCells()) {
            int x = c.getX();
            int y = c.getY();
            if (y >= 0 && y < h && x >= 0 && x < w) {
                switch (c.getType()) {
                    case WALL:  grid[y][x] = 0; break;
                    case EMPTY: grid[y][x] = 1; break;
                    case START: grid[y][x] = 2; break;
                    case GOAL:
                        // If a DMG marker exists, the real goal behaves as an empty cell for the WebView's game engine,
                        // as Maze only supports a single goal (which we prioritize as DMG).
                        grid[y][x] = hasDmg ? 1 : 3;
                        break;
                    case DMG:
                        // DMG marker always behaves as the goal if present.
                        grid[y][x] = 3;
                        break;
                    default:    grid[y][x] = 1; break;
                }
            }
        }
        return grid;
    }

    /**
     * Finds the START cell in the map (for nd and pegman position).
     */
    public Cell getStartCell(GridMap map) {
        if (map == null) return null;
        for (Cell c : map.getCells()) {
            if (c.getType() == CellType.START) return c;
        }
        return null;
    }

    /**
     * Finds the GOAL cell in the map (for od).
     */
    public Cell getGoalCell(GridMap map) {
        if (map == null) return null;
        for (Cell c : map.getCells()) {
            if (c.getType() == CellType.GOAL) return c;
        }
        return null;
    }

    /**
     * Finds the Direct Manipulation Goal (DMG) cell in the map (if any).
     * DMG is an intermediate target used for synthesis, distinct from the level's GOAL.
     */
    public Cell getDmgCell(GridMap map) {
        if (map == null) return null;
        for (Cell c : map.getCells()) {
            if (c.getType() == CellType.DMG) return c;
        }
        return null;
    }

    /**
     * Blockly Maze T value: NORTH=0, EAST=1, SOUTH=2, WEST=3.
     */
    public int directionToT(Direction d) {
        if (d == null) return 1;
        switch (d) {
            case NORTH: return 0;
            case EAST:  return 1;
            case SOUTH: return 2;
            case WEST:  return 3;
            default:    return 1;
        }
    }
}
