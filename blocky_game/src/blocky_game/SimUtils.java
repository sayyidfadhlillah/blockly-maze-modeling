package blocky_game;

import blocky.BlockyPackage;
import blocky.Body;
import blocky.Cell;
import blocky.CellType;
import blocky.Container;
import blocky.Direction;
import blocky.IfStmt;
import blocky.Level;
import blocky.Loop;
import blocky.Statement;

/**
 * Shared simulation utilities to ensure consistency across UI, Debugger, and Feedback services.
 */
public final class SimUtils {
    private SimUtils() {}

    /**
     * Identifies which cell type counts as the "winning" goal for the given level.
     * Prioritizes DMG (Direct Manipulation Goal) if it exists, otherwise GOAL.
     */
    public static CellType determineWinCellType(Level level) {
        if (level == null || level.getMap() == null) return CellType.GOAL;
        for (Cell c : level.getMap().getCells()) {
            if (c != null && c.getType() == CellType.DMG) {
                return CellType.DMG;
            }
        }
        return CellType.GOAL;
    }

    /**
     * Infers a stable starting orientation if one is not explicitly set in the model.
     * Logic: pick the first non-wall neighbor (N, E, S, W order), fallback to NORTH.
     */
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

    /**
     * Checks if a solution violates the constraints of a level (max blocks, allowed control flow).
     */
    public static boolean violatesLevelConstraints(Level level, Body solution) {
        if (level == null || solution == null) {
            return false;
        }

        int maxBlocks = level.getMaxBlocks();
        int blocks = countStatements(solution);
        if (maxBlocks > 0 && blocks > maxBlocks) {
            return true;
        }

        if (!level.isAllowLoops() && containsLoop(solution)) {
            return true;
        }

        if (!level.isAllowConditionals() && containsIf(solution)) {
            return true;
        }

        return false;
    }

    public static int countStatements(Body body) {
        if (body == null) return 0;
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
        if (body == null) return false;
        return containsLoop(body.getFirstContainer());
    }

    private static boolean containsLoop(Container c) {
        Container cur = c;
        while (cur != null) {
            Statement s = cur.getStatement();
            if (s instanceof Loop) return true;
            if (s instanceof IfStmt) {
                IfStmt i = (IfStmt) s;
                if (containsLoop(i.getThenBody()) || containsLoop(i.getElseBody())) return true;
            }
            cur = cur.getNext();
        }
        return false;
    }

    private static boolean containsIf(Body body) {
        if (body == null) return false;
        return containsIf(body.getFirstContainer());
    }

    private static boolean containsIf(Container c) {
        Container cur = c;
        while (cur != null) {
            Statement s = cur.getStatement();
            if (s != null) {
                if (s instanceof IfStmt) return true;
                if (s instanceof Loop) {
                    if (containsIf(((Loop) s).getBody())) return true;
                }
            }
            cur = cur.getNext();
        }
        return false;
    }
}
