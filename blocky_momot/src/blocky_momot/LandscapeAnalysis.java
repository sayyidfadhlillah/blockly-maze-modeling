package blocky_momot;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

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
import blocky.Game;
import blocky.GameStatus;
import blocky.IfStmt;
import blocky.Level;
import blocky.Loop;
import blocky.Statement;

/**
 * Experiment E1 of Exploration-Proposal.md: is an objective informative, i.e. does a better value mean a
 * program that is fewer block edits away from a solution? Measured without running any search.
 *
 * For one level it enumerates every program up to a block limit, in the block vocabulary of the rule set
 * (bodies may be empty, as the rules create them), evaluates each program, computes the block edit distance
 * to the nearest solution, and reports per objective how well its values follow that distance.
 *
 * Usage: LandscapeAnalysis &lt;level.xmi&gt; &lt;no_conds|no_else|full&gt; &lt;maxBlocks&gt; &lt;outDir&gt; [wrap]
 *
 * With the optional last argument "wrap", two more single edits are allowed (Exploration-Proposal.md, P1; same
 * semantics as the *_wrap.henshin rules): wrap = a block and everything after it in the same body moves into a
 * new loop / if / if-else; unwrap = a loop, if or if-else that is the last block of its body (else branch empty)
 * is replaced by its content. Without it the analysis is the one of section 8.
 *
 * Program text: F/L/R = move forward / turn left / turn right, W(..) = repeat until goal,
 * a(..) l(..) r(..) = if path ahead / left / right; in the full vocabulary an if is a(then|else).
 */
public final class LandscapeAnalysis {

    private static final int PENALTY = 100000;

    // Candidate definitions of "Progress". All are minimised.
    private static final String[] OBJECTIVES = {
        "closestToGoal_official",        // as the search computes it (BlockySimulator.distanceToGoalOrPenalty)
        "minDistanceVisited",            // smallest distance over every cell the robot visits
        "finalDistance",                 // distance of the cell where the robot ends
        "minDist_thenNoCrash",           // minDistanceVisited, ties broken by not ending in a crash
        "minDist_thenCells",             // minDistanceVisited, ties broken by more distinct cells visited
        "minDist_thenNoCrash_thenCells", // both tie-breakers
        "cellsVisited",                  // more distinct cells visited, on its own
        // Candidates for a second guiding objective. The robot is started at every cell of the shortest
        // start-to-goal route, with the heading it has when it arrives there.
        "suffixCoverage",                // number of route cells from which the program reaches the goal
        "routeFollowing",                // route cells followed in order from each route cell, summed
        "programShape"                   // reference only: has a loop, has an if inside a loop
    };

    private static final class Node {
        final char type;      // F L R, W = loop, a l r = if (condition)
        final List<Node> a;   // loop body or then-body
        final List<Node> b;   // else-body (full vocabulary only)
        final String text;

        Node(char type, List<Node> a, List<Node> b) {
            this.type = type;
            this.a = a;
            this.b = b;
            if (a == null) {
                text = String.valueOf(type);
            } else {
                text = type + "(" + text(a) + (b != null ? "|" + text(b) : "") + ")";
            }
        }

        boolean isIf() {
            return type == 'a' || type == 'l' || type == 'r';
        }

        boolean childless() {
            return a == null || (a.isEmpty() && (b == null || b.isEmpty()));
        }
    }

    private static String text(List<Node> seq) {
        StringBuilder sb = new StringBuilder();
        for (Node n : seq) sb.append(n.text);
        return sb.toString();
    }

    // ---- vocabulary ----
    private final boolean loops = true;
    private final boolean ifs;
    private final boolean elseBody;
    private final int maxBlocks;
    private final boolean wrap;
    private final String[] childlessStatements;

    // ---- maze ----
    private final Level level;
    private int[][] adj;      // per cell: top, right, bottom, left (index or -1)
    private boolean[] wall;
    private boolean[] win;
    private int[] cellDist;   // maze distance to the goal, -1 if unreachable
    private int startCell;
    private int startDir;
    private int maxSteps;
    private int[] route;      // cells of the shortest route from start to goal, goal included
    private int[] routeDir;   // heading on arrival at route[i]

    private LandscapeAnalysis(Level level, String ruleSet, int maxBlocks, boolean wrap) {
        this.level = level;
        this.maxBlocks = maxBlocks;
        this.wrap = wrap;
        this.ifs = !"no_conds".equals(ruleSet);
        this.elseBody = "full".equals(ruleSet);
        List<String> c = new ArrayList<>(Arrays.asList("F", "L", "R", "W()"));
        if (ifs) {
            for (char k : new char[] { 'a', 'l', 'r' }) c.add(k + (elseBody ? "(|)" : "()"));
        }
        this.childlessStatements = c.toArray(new String[0]);
        readMaze();
    }

    private void readMaze() {
        List<Cell> cells = level.getMap().getCells();
        Map<Cell, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < cells.size(); i++) index.put(cells.get(i), i);
        CellType winType = BlockySimulator.determineWinCellType(level);
        int n = cells.size();
        adj = new int[n][4];
        wall = new boolean[n];
        win = new boolean[n];
        cellDist = new int[n];
        Arrays.fill(cellDist, -1);
        startCell = -1;
        for (int i = 0; i < n; i++) {
            Cell c = cells.get(i);
            Cell[] nb = { c.getTop(), c.getRight(), c.getBottom(), c.getLeft() };
            for (int d = 0; d < 4; d++) adj[i][d] = nb[d] == null ? -1 : index.get(nb[d]);
            wall[i] = c.getType() == CellType.WALL;
            win[i] = c.getType() == winType;
            if (startCell < 0 && c.getType() == CellType.START) startCell = i;
        }
        if (startCell < 0) startCell = 0;
        startDir = BlockySimulator.determineStartOrientation(level, cells.get(startCell)).getValue();
        maxSteps = level.getMap().getWidth() * level.getMap().getHeight() * 2;

        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++) {
            if (win[i]) {
                cellDist[i] = 0;
                queue[tail++] = i;
            }
        }
        while (head < tail) {
            int cur = queue[head++];
            for (int d = 0; d < 4; d++) {
                int t = adj[cur][d];
                if (t < 0 || wall[t] || cellDist[t] >= 0) continue;
                cellDist[t] = cellDist[cur] + 1;
                queue[tail++] = t;
            }
        }

        int len = Math.max(0, cellDist[startCell]);
        route = new int[len + 1];
        routeDir = new int[len + 1];
        route[0] = startCell;
        routeDir[0] = startDir;
        for (int i = 1; i <= len; i++) {
            for (int d = 0; d < 4; d++) {
                int t = adj[route[i - 1]][d];
                if (t >= 0 && !wall[t] && cellDist[t] == cellDist[route[i - 1]] - 1) {
                    route[i] = t;
                    routeDir[i] = d;
                    break;
                }
            }
        }
    }

    // ---- enumeration ----
    private List<List<String>> seqBySize;

    private List<String> enumerate() {
        seqBySize = new ArrayList<>();
        seqBySize.add(new ArrayList<>(List.of("")));
        for (int n = 1; n <= maxBlocks; n++) {
            List<String> out = new ArrayList<>();
            for (int k = 1; k <= n; k++) {
                List<String> rest = seqBySize.get(n - k);
                for (String first : statements(k)) {
                    for (String r : rest) out.add(first + r);
                }
            }
            seqBySize.add(out);
        }
        List<String> all = new ArrayList<>();
        for (List<String> l : seqBySize) all.addAll(l);
        return all;
    }

    /** All single statements with exactly n blocks; bodies use the already enumerated smaller sequences. */
    private List<String> statements(int n) {
        List<String> out = new ArrayList<>();
        if (n == 1) out.addAll(List.of("F", "L", "R"));
        if (loops) {
            for (String body : seqBySize.get(n - 1)) out.add("W(" + body + ")");
        }
        if (ifs) {
            for (char c : new char[] { 'a', 'l', 'r' }) {
                if (!elseBody) {
                    for (String then : seqBySize.get(n - 1)) out.add(c + "(" + then + ")");
                } else {
                    for (int t = 0; t <= n - 1; t++) {
                        for (String then : seqBySize.get(t)) {
                            for (String els : seqBySize.get(n - 1 - t)) out.add(c + "(" + then + "|" + els + ")");
                        }
                    }
                }
            }
        }
        return out;
    }

    // ---- parsing ----
    private int parsePos;

    private List<Node> parse(String s) {
        parsePos = 0;
        return parseSeq(s);
    }

    private List<Node> parseSeq(String s) {
        List<Node> out = new ArrayList<>();
        while (parsePos < s.length()) {
            char c = s.charAt(parsePos);
            if (c == ')' || c == '|') break;
            parsePos++;
            if (c == 'F' || c == 'L' || c == 'R') {
                out.add(new Node(c, null, null));
                continue;
            }
            parsePos++; // (
            List<Node> a = parseSeq(s);
            List<Node> b = null;
            if (parsePos < s.length() && s.charAt(parsePos) == '|') {
                parsePos++;
                b = parseSeq(s);
            }
            parsePos++; // )
            out.add(new Node(c, a, b));
        }
        return out;
    }

    /** 0, 1 or 2: has a loop, has an if inside a loop. */
    private static int shape(List<Node> seq, boolean insideLoop) {
        boolean loop = false, ifInLoop = false;
        for (Node x : seq) {
            if (x.type == 'W') loop = true;
            if (x.isIf() && insideLoop) ifInLoop = true;
            if (x.a != null) {
                int inner = Math.max(shape(x.a, insideLoop || x.type == 'W'),
                        x.b == null ? 0 : shape(x.b, insideLoop || x.type == 'W'));
                if (inner >= 2) ifInLoop = true;
                if (inner >= 1) loop = true;
            }
        }
        return ifInLoop ? 2 : loop ? 1 : 0;
    }

    private static int blocks(List<Node> seq) {
        int n = 0;
        for (Node x : seq) {
            n++;
            if (x.a != null) n += blocks(x.a);
            if (x.b != null) n += blocks(x.b);
        }
        return n;
    }

    // ---- one-edit neighbours (insert or delete one childless block, change one kind or condition) ----
    private void neighbours(List<Node> seq, String pre, String post, boolean allowInsert, List<String> out) {
        int k = seq.size();
        String[] suffix = new String[k + 1];
        suffix[k] = "";
        for (int i = k - 1; i >= 0; i--) suffix[i] = seq.get(i).text + suffix[i + 1];
        String prefix = "";
        for (int i = 0; i <= k; i++) {
            if (allowInsert) {
                for (String ns : childlessStatements) out.add(pre + prefix + ns + suffix[i] + post);
            }
            if (i == k) break;
            Node n = seq.get(i);
            String rest = suffix[i + 1];
            if (wrap) {
                // Wrap this block and everything after it in a new loop / if / if-else (adds one block).
                if (allowInsert) {
                    out.add(pre + prefix + "W(" + suffix[i] + ")" + post);
                    if (ifs) {
                        for (char c : new char[] { 'a', 'l', 'r' }) {
                            out.add(pre + prefix + c + "(" + suffix[i] + (elseBody ? "|" : "") + ")" + post);
                        }
                    }
                }
                // Unwrap a loop / if / if-else that is the last block of its body, if its else branch is empty.
                // An empty body is left out: that edit is the same as deleting the childless block.
                if (i == k - 1 && n.a != null && !n.a.isEmpty() && (n.b == null || n.b.isEmpty())) {
                    out.add(pre + prefix + text(n.a) + post);
                }
            }
            if (n.childless()) out.add(pre + prefix + rest + post);
            if (n.a == null) {
                for (char c : new char[] { 'F', 'L', 'R' }) {
                    if (c != n.type) out.add(pre + prefix + c + rest + post);
                }
            } else {
                if (n.isIf()) {
                    for (char c : new char[] { 'a', 'l', 'r' }) {
                        if (c != n.type) out.add(pre + prefix + c + n.text.substring(1) + rest + post);
                    }
                }
                String open = pre + prefix + n.type + "(";
                if (n.b == null) {
                    neighbours(n.a, open, ")" + rest + post, allowInsert, out);
                } else {
                    neighbours(n.a, open, "|" + text(n.b) + ")" + rest + post, allowInsert, out);
                    neighbours(n.b, open + text(n.a) + "|", ")" + rest + post, allowInsert, out);
                }
            }
            prefix += n.text;
        }
    }

    // ---- own simulation (same rules as BlockySimulator.run, plus what the robot visited) ----
    private int pos, dir, steps, status; // status: 0 running, 1 won, 2 crashed
    private boolean[] seen;
    private int seenCount, minVisited;

    // Route following: index of the next route cell the robot must enter, or -1 once it has left the route.
    private int routeNext, routeFollowed;

    private void simulate(List<Node> program) {
        simulateFrom(program, 0);
    }

    private void simulateFrom(List<Node> program, int routeIndex) {
        pos = route[routeIndex];
        dir = routeDir[routeIndex];
        routeNext = routeIndex + 1;
        routeFollowed = 0;
        steps = 0;
        status = 0;
        seen = new boolean[wall.length];
        seenCount = 0;
        minVisited = Integer.MAX_VALUE;
        visit();
        execSeq(program);
    }

    private void visit() {
        if (!seen[pos]) {
            seen[pos] = true;
            seenCount++;
        }
        int d = cellDist[pos];
        if (d >= 0 && d < minVisited) minVisited = d;
    }

    private void execSeq(List<Node> seq) {
        for (Node n : seq) {
            if (status != 0) return;
            execOne(n);
        }
    }

    private void execOne(Node n) {
        steps++;
        switch (n.type) {
        case 'F': {
            int t = adj[pos][dir];
            if (t < 0 || wall[t]) {
                status = 2;
            } else {
                pos = t;
                if (win[t]) status = 1;
                visit();
                if (routeNext >= 0 && routeNext < route.length && route[routeNext] == t) {
                    routeNext++;
                    routeFollowed++;
                } else {
                    routeNext = -1;
                }
            }
            break;
        }
        case 'L':
            dir = (dir + 3) % 4;
            break;
        case 'R':
            dir = (dir + 1) % 4;
            break;
        case 'W':
            while (status == 0 && !win[pos]) {
                if (steps > maxSteps) {
                    status = 2;
                    break;
                }
                int before = steps;
                execSeq(n.a);
                if (steps == before) {
                    status = 2;
                    break;
                }
            }
            break;
        default: {
            int look = n.type == 'a' ? dir : n.type == 'l' ? (dir + 3) % 4 : (dir + 1) % 4;
            int t = adj[pos][look];
            if (t >= 0 && !wall[t]) {
                execSeq(n.a);
            } else if (n.b != null) {
                execSeq(n.b);
            }
        }
        }
    }

    // ---- EMF program, for the values exactly as the search computes them ----
    private static final BlockyFactory F = BlockyFactory.eINSTANCE;

    private Body toBody(List<Node> seq) {
        Body body = F.createBody();
        Container prev = null;
        for (Node n : seq) {
            Container c = F.createContainer();
            c.setStatement(toStatement(n));
            if (prev == null) body.setFirstContainer(c);
            else prev.setNext(c);
            prev = c;
        }
        return body;
    }

    private Statement toStatement(Node n) {
        if (n.a == null) {
            AtomicStatement s = F.createAtomicStatement();
            s.setKind(n.type == 'F' ? AtomicStatementKind.MOVE_FORWARD
                    : n.type == 'L' ? AtomicStatementKind.TURN_LEFT : AtomicStatementKind.TURN_RIGHT);
            return s;
        }
        if (n.type == 'W') {
            Loop l = F.createLoop();
            l.setBody(toBody(n.a));
            return l;
        }
        IfStmt i = F.createIfStmt();
        i.setCondition(n.type == 'a' ? ConditionKind.CHECK_FORWARD
                : n.type == 'l' ? ConditionKind.CHECK_LEFT : ConditionKind.CHECK_RIGHT);
        i.setThenBody(toBody(n.a));
        if (n.b != null) i.setElseBody(toBody(n.b));
        return i;
    }

    // ---- statistics ----
    private static double[] ranks(long[] v, boolean[] use) {
        TreeMap<Long, Integer> counts = new TreeMap<>();
        for (int i = 0; i < v.length; i++) {
            if (use[i]) counts.merge(v[i], 1, Integer::sum);
        }
        Map<Long, Double> rank = new HashMap<>();
        long before = 0;
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {
            rank.put(e.getKey(), before + (e.getValue() + 1) / 2.0);
            before += e.getValue();
        }
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            if (use[i]) r[i] = rank.get(v[i]);
        }
        return r;
    }

    /** Spearman rank correlation; positive means: better (smaller) objective value, fewer edits to a solution. */
    private static double spearman(long[] x, long[] y, boolean[] use) {
        double[] rx = ranks(x, use), ry = ranks(y, use);
        double n = 0, sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        for (int i = 0; i < x.length; i++) {
            if (!use[i]) continue;
            n++;
            sx += rx[i];
            sy += ry[i];
            sxx += rx[i] * rx[i];
            syy += ry[i] * ry[i];
            sxy += rx[i] * ry[i];
        }
        double cov = sxy - sx * sy / n, vx = sxx - sx * sx / n, vy = syy - sy * sy / n;
        return (vx <= 0 || vy <= 0) ? Double.NaN : cov / Math.sqrt(vx * vy);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.err.println("Usage: LandscapeAnalysis <level.xmi> <no_conds|no_else|full> <maxBlocks> <outDir> [wrap]");
            return;
        }
        String ruleSet = args[1];
        int maxBlocks = Integer.parseInt(args[2]);
        File outDir = new File(args[3]);
        outDir.mkdirs();
        Level level = loadLevel(args[0]);
        boolean wrap = args.length > 4 && "wrap".equals(args[4]);
        new LandscapeAnalysis(level, ruleSet, maxBlocks, wrap).run("level" + level.getId(), ruleSet, outDir);
    }

    private void run(String name, String ruleSet, File outDir) throws IOException {
        long t0 = System.currentTimeMillis();
        List<String> programs = enumerate();
        int n = programs.size();
        Map<String, Integer> index = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) index.put(programs.get(i), i);
        System.out.printf("%s %s maxBlocks=%d: %d programs (%.1fs)%n", name, ruleSet, maxBlocks, n, sec(t0));

        int k = OBJECTIVES.length;
        long[][] obj = new long[k][n];
        boolean[] solution = new boolean[n];
        int[] size = new int[n];
        int simMismatch = 0, officialDiffers = 0, solutions = 0, minSolutionBlocks = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            List<Node> p = parse(programs.get(i));
            size[i] = blocks(p);

            level.setSolution(toBody(p));
            boolean won = BlockySimulator.run(level) == GameStatus.WON;
            int actions = BlockySimulator.simulationSteps(level);
            int official = BlockySimulator.distanceToGoalOrPenalty(level, PENALTY);

            simulate(p);
            if ((status == 1) != won || steps != actions) simMismatch++;
            int minDist = minVisited == Integer.MAX_VALUE ? PENALTY : minVisited;
            int finalDist = cellDist[pos] < 0 ? PENALTY : cellDist[pos];
            int crash = status == 2 ? 1 : 0;
            int cellsLeft = 999 - Math.min(999, seenCount);
            if (official != minDist) officialDiffers++;

            solution[i] = won;
            if (won) {
                solutions++;
                minSolutionBlocks = Math.min(minSolutionBlocks, size[i]);
            }
            obj[0][i] = official;
            obj[1][i] = minDist;
            obj[2][i] = finalDist;
            obj[3][i] = minDist * 4L + crash;
            obj[4][i] = minDist * 1000L + cellsLeft;
            obj[5][i] = minDist * 4000L + crash * 1000L + cellsLeft;
            obj[6][i] = cellsLeft;

            int reached = won ? 1 : 0, followed = routeFollowed;
            for (int r = 1; r < route.length - 1; r++) {
                simulateFrom(p, r);
                if (status == 1) reached++;
                followed += routeFollowed;
            }
            obj[7][i] = 1000 - reached;
            obj[8][i] = 100000 - followed;
            obj[9][i] = 2 - shape(p, false);
        }
        level.setSolution(null);
        System.out.printf("evaluated: %d solutions, smallest %s blocks; own simulation differs from BlockySimulator "
                + "in %d programs; official closestToGoal differs from minDistanceVisited in %d programs (%.1fs)%n",
                solutions, solutions == 0 ? "-" : String.valueOf(minSolutionBlocks), simMismatch, officialDiffers, sec(t0));
        if (solutions == 0) {
            System.out.println("No solution within the block limit; nothing to measure.");
            return;
        }

        // Distance to the nearest solution: breadth-first search outwards from all solutions over single edits.
        int[] dist = new int[n];
        Arrays.fill(dist, -1);
        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++) {
            if (solution[i]) {
                dist[i] = 0;
                queue[tail++] = i;
            }
        }
        List<String> nb = new ArrayList<>();
        while (head < tail) {
            int cur = queue[head++];
            nb.clear();
            neighbours(parse(programs.get(cur)), "", "", size[cur] < maxBlocks, nb);
            for (String s : nb) {
                Integer j = index.get(s);
                if (j != null && dist[j] < 0) {
                    dist[j] = dist[cur] + 1;
                    queue[tail++] = j;
                }
            }
        }
        int unreached = n - tail;
        System.out.printf("distances done, %d programs not connected to a solution (%.1fs)%n", unreached, sec(t0));

        // BlockyProgramDistance has no wrap or unwrap, so with wrap the distance is checked for symmetry instead.
        int distanceMismatch = wrap ? checkSymmetry(programs, index, size) : checkDistances(programs, solution, dist);
        System.out.printf(wrap ? "edit symmetry check (b one edit from a implies a one edit from b): %d mismatches (%.1fs)%n"
                : "distance check against BlockyProgramDistance: %d mismatches (%.1fs)%n", distanceMismatch, sec(t0));

        boolean[] nonSol = new boolean[n];
        boolean[] nonSolMaxSize = new boolean[n];
        long[] distL = new long[n];
        int nonSolCount = 0, maxDist = 0;
        double distSum = 0;
        for (int i = 0; i < n; i++) {
            distL[i] = dist[i];
            nonSol[i] = !solution[i] && dist[i] > 0;
            nonSolMaxSize[i] = nonSol[i] && size[i] == maxBlocks;
            if (nonSol[i]) {
                nonSolCount++;
                distSum += dist[i];
                maxDist = Math.max(maxDist, dist[i]);
            }
        }

        // One-edit neighbour statistics.
        long[] closerBetter = new long[k], closerWorse = new long[k], fartherBetter = new long[k], fartherWorse = new long[k];
        long closerPairs = 0, fartherPairs = 0;
        long[] localOptima = new long[k];
        double[] localOptimaDist = new double[k];
        boolean[] hasBetter = new boolean[k];
        for (int i = 0; i < n; i++) {
            if (!nonSol[i]) continue;
            nb.clear();
            neighbours(parse(programs.get(i)), "", "", size[i] < maxBlocks, nb);
            Arrays.fill(hasBetter, false);
            for (String s : nb) {
                Integer jj = index.get(s);
                if (jj == null) continue;
                int j = jj;
                for (int o = 0; o < k; o++) {
                    if (obj[o][j] < obj[o][i]) hasBetter[o] = true;
                }
                if (solution[j] || dist[j] == dist[i]) continue;
                boolean closer = dist[j] < dist[i];
                if (closer) closerPairs++;
                else fartherPairs++;
                for (int o = 0; o < k; o++) {
                    if (obj[o][j] < obj[o][i]) {
                        if (closer) closerBetter[o]++;
                        else fartherBetter[o]++;
                    } else if (obj[o][j] > obj[o][i]) {
                        if (closer) closerWorse[o]++;
                        else fartherWorse[o]++;
                    }
                }
            }
            for (int o = 0; o < k; o++) {
                if (!hasBetter[o]) {
                    localOptima[o]++;
                    localOptimaDist[o] += dist[i];
                }
            }
        }
        System.out.printf("neighbour statistics done (%.1fs)%n", sec(t0));

        try (PrintWriter w = new PrintWriter(new File(outDir, name + "_summary.csv"), StandardCharsets.UTF_8)) {
            w.println("level,ruleSet,maxBlocks,programs,solutions,minSolutionBlocks,simMismatch,distanceMismatch,"
                    + "officialDiffersFromMinVisited,objective,spearmanAll,spearmanMaxSize,quantileDist1,quantileDist2,"
                    + "programsDist1,closerBetter,closerWorse,fartherBetter,fartherWorse,closerPairs,fartherPairs,"
                    + "localOptimaShare,localOptimaMeanDist,meanDistAll,distinctValues,plateauShare");
            for (int o = 0; o < k; o++) {
                TreeMap<Long, Integer> counts = new TreeMap<>();
                for (int i = 0; i < n; i++) {
                    if (nonSol[i]) counts.merge(obj[o][i], 1, Integer::sum);
                }
                int biggest = 0;
                Map<Long, Double> quantile = new HashMap<>(); // 1 = best value, 0.5 = like a random program
                long better = 0;
                for (Map.Entry<Long, Integer> e : counts.entrySet()) {
                    biggest = Math.max(biggest, e.getValue());
                    quantile.put(e.getKey(), 1.0 - (better + e.getValue() / 2.0) / nonSolCount);
                    better += e.getValue();
                }
                double q1 = 0, q2 = 0;
                int n1 = 0, n2 = 0;
                for (int i = 0; i < n; i++) {
                    if (!nonSol[i]) continue;
                    if (dist[i] == 1) {
                        q1 += quantile.get(obj[o][i]);
                        n1++;
                    } else if (dist[i] == 2) {
                        q2 += quantile.get(obj[o][i]);
                        n2++;
                    }
                }
                w.printf(Locale.US, "%s,%s,%d,%d,%d,%d,%d,%d,%d,%s,%.4f,%.4f,%.4f,%.4f,%d,%.4f,%.4f,%.4f,%.4f,%d,%d,%.4f,%.3f,%.3f,%d,%.4f%n",
                        name, ruleSet, maxBlocks, n, solutions, minSolutionBlocks, simMismatch, distanceMismatch, officialDiffers,
                        OBJECTIVES[o], spearman(obj[o], distL, nonSol), spearman(obj[o], distL, nonSolMaxSize),
                        n1 == 0 ? Double.NaN : q1 / n1, n2 == 0 ? Double.NaN : q2 / n2, n1,
                        share(closerBetter[o], closerPairs), share(closerWorse[o], closerPairs),
                        share(fartherBetter[o], fartherPairs), share(fartherWorse[o], fartherPairs), closerPairs, fartherPairs,
                        (double) localOptima[o] / nonSolCount,
                        localOptima[o] == 0 ? Double.NaN : localOptimaDist[o] / localOptima[o], distSum / nonSolCount,
                        counts.size(), (double) biggest / nonSolCount);
            }
        }

        try (PrintWriter w = new PrintWriter(new File(outDir, name + "_by_distance.csv"), StandardCharsets.UTF_8)) {
            StringBuilder h = new StringBuilder("level,distance,programs");
            for (String o : OBJECTIVES) h.append(",mean_").append(o);
            w.println(h);
            for (int d = 0; d <= maxDist; d++) {
                int count = 0;
                double[] sum = new double[k];
                for (int i = 0; i < n; i++) {
                    if (dist[i] != d) continue;
                    count++;
                    for (int o = 0; o < k; o++) sum[o] += obj[o][i];
                }
                if (count == 0) continue;
                StringBuilder row = new StringBuilder(name + "," + d + "," + count);
                for (int o = 0; o < k; o++) row.append(String.format(Locale.US, ",%.3f", sum[o] / count));
                w.println(row);
            }
        }

        // Gate sweep (Exploration-Proposal.md, gated objectives): for each threshold T, the programs that would pass
        // a gate "Progress <= T" are the solutions plus the non-solutions with official Progress <= T. Written to a
        // separate file; the other outputs are unchanged.
        try (PrintWriter w = new PrintWriter(new File(outDir, name + "_gate.csv"), StandardCharsets.UTF_8)) {
            w.println("level,wrap,routeLength,minSolutionBlocks,threshold,passingNonSolutions,passShareOfNonSolutions,"
                    + "solutionsShareOfPassing,meanDistPassing,meanDistFailing,shareDist1or2Passing,shareSmallerThanSmallestSolutionPassing,"
                    + "meanBlocksPassing");
            int routeLen = Math.max(0, cellDist[startCell]);
            for (int t = 0; t <= routeLen; t++) {
                long pass = 0, fail = 0, near = 0, tiny = 0;
                double dPass = 0, dFail = 0, bPass = 0;
                for (int i = 0; i < n; i++) {
                    if (!nonSol[i]) continue;
                    if (obj[0][i] <= t) {
                        pass++;
                        dPass += dist[i];
                        bPass += size[i];
                        if (dist[i] <= 2) near++;
                        if (size[i] < minSolutionBlocks) tiny++;
                    } else {
                        fail++;
                        dFail += dist[i];
                    }
                }
                w.printf(Locale.US, "%s,%s,%d,%d,%d,%d,%.4f,%.6f,%.3f,%.3f,%.4f,%.4f,%.3f%n", name, wrap, routeLen, minSolutionBlocks, t,
                        pass, (double) pass / nonSolCount, (double) solutions / (solutions + pass),
                        pass == 0 ? Double.NaN : dPass / pass, fail == 0 ? Double.NaN : dFail / fail,
                        pass == 0 ? Double.NaN : (double) near / pass, pass == 0 ? Double.NaN : (double) tiny / pass,
                        pass == 0 ? Double.NaN : bPass / pass);
            }
        }

        try (PrintWriter w = new PrintWriter(new File(outDir, name + "_solutions.txt"), StandardCharsets.UTF_8)) {
            int[] perSize = new int[maxBlocks + 1];
            for (int i = 0; i < n; i++) {
                if (solution[i]) perSize[size[i]]++;
            }
            w.println("solutions per block count: " + Arrays.toString(perSize));
            int listed = 0;
            for (int i = 0; i < n && listed < 200; i++) {
                if (solution[i]) {
                    w.println(size[i] + " " + programs.get(i));
                    listed++;
                }
            }
        }
        System.out.printf("%s finished (%.1fs)%n", name, sec(t0));
    }

    /** Compares the search-based distance with BlockyProgramDistance to the nearest solution, on a random sample. */
    private int checkDistances(List<String> programs, boolean[] solution, int[] dist) {
        List<Body> solutionBodies = new ArrayList<>();
        for (int i = 0; i < programs.size() && solutionBodies.size() < 2000; i++) {
            if (solution[i]) solutionBodies.add(toBody(parse(programs.get(i))));
        }
        boolean allSolutions = true;
        int count = 0;
        for (boolean s : solution) {
            if (s) count++;
        }
        if (count > solutionBodies.size()) allSolutions = false;
        Random rnd = new Random(1);
        int mismatches = 0;
        for (int s = 0; s < 300; s++) {
            int i = rnd.nextInt(programs.size());
            Body b = toBody(parse(programs.get(i)));
            int best = Integer.MAX_VALUE;
            for (Body sol : solutionBodies) best = Math.min(best, BlockyProgramDistance.programDistance(b, sol));
            // With a capped solution list the reference can only be too large, never too small.
            if (allSolutions ? best != dist[i] : best < dist[i]) mismatches++;
        }
        return mismatches;
    }

    /** On a random sample: every enumerated neighbour of a program must have that program as a neighbour. */
    private int checkSymmetry(List<String> programs, Map<String, Integer> index, int[] size) {
        Random rnd = new Random(1);
        int mismatches = 0;
        List<String> nb = new ArrayList<>(), back = new ArrayList<>();
        for (int s = 0; s < 3000; s++) {
            int i = rnd.nextInt(programs.size());
            nb.clear();
            neighbours(parse(programs.get(i)), "", "", size[i] < maxBlocks, nb);
            for (String t : nb) {
                Integer j = index.get(t);
                if (j == null) continue;
                back.clear();
                neighbours(parse(t), "", "", size[j] < maxBlocks, back);
                if (!back.contains(programs.get(i))) mismatches++;
            }
        }
        return mismatches;
    }

    private static double share(long part, long total) {
        return total == 0 ? Double.NaN : (double) part / total;
    }

    private static double sec(long t0) {
        return (System.currentTimeMillis() - t0) / 1000.0;
    }

    private static Level loadLevel(String path) throws IOException {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().putIfAbsent("xmi", new XMIResourceFactoryImpl());
        EPackage pkg = BlockyPackage.eINSTANCE;
        EPackage.Registry.INSTANCE.put(pkg.getNsURI(), pkg);
        ResourceSet rs = new ResourceSetImpl();
        rs.getPackageRegistry().put(pkg.getNsURI(), pkg);
        Resource r = rs.getResource(URI.createFileURI(new File(path).getAbsolutePath()), true);
        return ((Game) r.getContents().get(0)).getLevels().get(0);
    }
}
