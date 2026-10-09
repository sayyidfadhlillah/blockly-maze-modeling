package blocky_momot;

import java.io.File;
import java.io.IOException;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;

import blocky.Body;
import blocky.BlockyPackage;
import blocky.Container;
import blocky.Game;
import blocky.IfStmt;
import blocky.Level;
import blocky.Loop;
import blocky.Statement;

/**
 * Small helper utilities for Blocky program metrics (e.g., statement counts).
 *
 * Kept in Java so MOMoT fitness definitions stay concise (similar to {@link BlockyProgramDistance}).
 */
public final class BlockyProgramMetrics {
    private BlockyProgramMetrics() {}

    /**
     * Infer a safe upper bound for MOMoT's {@code solutionLength} based on maxBlocks.
     *
     * Worst case: delete all existing statements and create exactly maxBlocks new ones.
     * So we return {@code existingStatementCount + maxBlocks}.
     */
    public static int inferSolutionLength(String gameXmiPath) {
        try {
            if (gameXmiPath == null || gameXmiPath.trim().isEmpty()) {
                return 10;
            }
            Game game = loadGame(gameXmiPath);
            if (game == null || game.getLevels().isEmpty() || game.getLevels().get(0) == null) {
                return 10;
            }
            Level level = game.getLevels().get(0);
            int existing = countStatements(level.getSolution());
            int maxBlocks = level.getMaxBlocks();
            if (maxBlocks <= 0) {
                maxBlocks = 5;
            }
            return Math.max(1, existing + maxBlocks);
        } catch (Throwable t) {
            System.err.println("[BlockyProgramMetrics] Could not load XMI for solution length inference (" + gameXmiPath + "): " + t.getMessage());
            return 10;
        }
    }

    public static int countStatements(Game game) {
        if (game == null || game.getLevels().isEmpty()) {
            return 0;
        }
        Level level = game.getLevels().get(0);
        return level == null ? 0 : countStatements(level.getSolution());
    }

    /** Count Blockly statements in a persisted {@link Game} XMI. */
    public static int countStatementsInXmi(String gameXmiPath) {
        try {
            return countStatements(loadGame(gameXmiPath));
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int countLoops(Game game) {
        if (game == null || game.getLevels().isEmpty()) {
            return 0;
        }
        Level level = game.getLevels().get(0);
        return level == null ? 0 : countLoops(level.getSolution());
    }

    public static int countConditionals(Game game) {
        if (game == null || game.getLevels().isEmpty()) {
            return 0;
        }
        Level level = game.getLevels().get(0);
        return level == null ? 0 : countConditionals(level.getSolution());
    }

    public static int countStatements(Body body) {
        if (body == null) {
            return 0;
        }
        int count = 0;
        Container c = body.getFirstContainer();
        while (c != null) {
            Statement s = c.getStatement();
            if (s != null) {
                count += 1;
                if (s instanceof Loop) {
                    count += countStatements(((Loop) s).getBody());
                } else if (s instanceof IfStmt) {
                    count += countStatements(((IfStmt) s).getThenBody());
                    count += countStatements(((IfStmt) s).getElseBody());
                }
            }
            c = c.getNext();
        }
        return count;
    }

    public static int countLoops(Body body) {
        if (body == null) {
            return 0;
        }
        int count = 0;
        Container c = body.getFirstContainer();
        while (c != null) {
            Statement s = c.getStatement();
            if (s instanceof Loop) {
                count += 1;
                count += countLoops(((Loop) s).getBody());
            } else if (s instanceof IfStmt) {
                // still visit nested bodies for loops
                count += countLoops(((IfStmt) s).getThenBody());
                count += countLoops(((IfStmt) s).getElseBody());
            }
            c = c.getNext();
        }
        return count;
    }

    public static int countConditionals(Body body) {
        if (body == null) {
            return 0;
        }
        int count = 0;
        Container c = body.getFirstContainer();
        while (c != null) {
            Statement s = c.getStatement();
            if (s instanceof IfStmt) {
                count += 1;
                count += countConditionals(((IfStmt) s).getThenBody());
                count += countConditionals(((IfStmt) s).getElseBody());
            } else if (s instanceof Loop) {
                // still visit nested bodies for conditionals
                count += countConditionals(((Loop) s).getBody());
            }
            c = c.getNext();
        }
        return count;
    }

    private static File resolveFile(String path) {
        if (path == null || path.isBlank()) return new File("blocky_game/model/input/1.xmi");
        File f = new File(path);
        if (f.isAbsolute() && f.exists()) return f;
        if (f.exists()) return f;
        File rel = new File(System.getProperty("user.dir"), path);
        if (rel.exists()) return rel;
        File f1 = new File("blocky_game", path);
        if (f1.exists()) return f1;
        File f2 = new File("blocky_momot", path);
        if (f2.exists()) return f2;
        File f3 = new File("/app", path);
        if (f3.exists()) return f3;
        File f4 = new File("blocky_game/model/input/1.xmi");
        if (f4.exists()) return f4;
        File f5 = new File("blocky_momot/model/input/1.xmi");
        if (f5.exists()) return f5;
        return f;
    }

    private static Game loadGame(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        try {
            Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().putIfAbsent("xmi", new XMIResourceFactoryImpl());

            EPackage pkg = BlockyPackage.eINSTANCE;
            EPackage.Registry.INSTANCE.put(pkg.getNsURI(), pkg);
            EPackage.Registry.INSTANCE.put(pkg.getName(), pkg);

            ResourceSet rs = new ResourceSetImpl();
            rs.getPackageRegistry().put(pkg.getNsURI(), pkg);
            rs.getPackageRegistry().put(pkg.getName(), pkg);

            File f = resolveFile(path);
            if (!f.exists() || !f.isFile()) {
                return null;
            }

            URI uri = URI.createFileURI(f.getAbsolutePath());
            Resource r = rs.getResource(uri, true);
            r.load(null);
            if (r.getContents().isEmpty() || !(r.getContents().get(0) instanceof Game)) {
                return null;
            }
            return (Game) r.getContents().get(0);
        } catch (Throwable t) {
            return null;
        }
    }
}
