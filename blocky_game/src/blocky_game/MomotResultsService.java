package blocky_game;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best-effort loader for MoMoT outputs (objectives + formation times + textual summaries + model XMIs).
 *
 * Contract (current repo conventions):
 * - outputs live under blocky_momot/output* + "/" (e.g. output, output2)
 * - objectives in objectives.pf (whitespace-separated numbers per line)
 * - formation times in times.pf (one timestamp in milliseconds per line)
 * - textual report in solutions.txt
 * - concrete solution models in output* + "/models/*.xmi"
 */
public final class MomotResultsService {

    public static final class SolutionEntry {
        public String outputDir;
        public String modelPath;
        public String objectiveLine; // e.g. "-1.0 15.0"
        public String summary;       // best-effort excerpt from solutions.txt
        public Long timeToFormMs;    // time in ms taken to form this solution
        public Integer generationToForm; // generation in which this solution first appeared
    }

    public static final class SearchMetrics {
        public Long timeToFirstGoalMs;
        public Integer generationOfFirstGoal;
    }

    public static SearchMetrics loadSearchMetrics(File outDir) {
        if (outDir == null || !outDir.isDirectory()) return null;
        SearchMetrics metrics = new SearchMetrics();
        File f = new File(outDir, "first_goal.txt");
        if (f.exists()) {
            List<String> lines = readLines(f);
            for (String line : lines) {
                String[] parts = line.split("[:=]");
                if (parts.length == 2) {
                    String k = parts[0].trim();
                    String v = parts[1].trim();
                    try {
                        if ("timeToFirstGoalMs".equalsIgnoreCase(k)) {
                            metrics.timeToFirstGoalMs = Long.parseLong(v);
                        } else if ("generationOfFirstGoal".equalsIgnoreCase(k)) {
                            metrics.generationOfFirstGoal = Integer.parseInt(v);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return metrics;
    }

    private static final Map<String, List<SolutionEntry>> LAST_VALID_ENTRIES_CACHE = new ConcurrentHashMap<>();

    private MomotResultsService() {}

    public static void clearCache() {
        LAST_VALID_ENTRIES_CACHE.clear();
    }

    public static void clearCacheForDir(File outDir) {
        if (outDir != null) {
            LAST_VALID_ENTRIES_CACHE.remove(outDir.getAbsolutePath());
        }
    }

    public static List<SolutionEntry> loadAll() {
        File base = new File("blocky_momot");
        if (!base.exists() || !base.isDirectory()) {
            return Collections.emptyList();
        }

        File[] outs = base.listFiles(f -> f != null && f.isDirectory() && f.getName().startsWith("output"));
        if (outs == null || outs.length == 0) return Collections.emptyList();

        List<File> outputDirs = new ArrayList<>();
        Collections.addAll(outputDirs, outs);
        outputDirs.sort(Comparator.comparing(File::getName));

        List<SolutionEntry> all = new ArrayList<>();
        for (File outDir : outputDirs) {
            all.addAll(loadFromOutputDir(outDir));
        }
        return all;
    }

    public static List<SolutionEntry> loadFromOutputDir(File outDir) {
        if (outDir == null || !outDir.isDirectory()) return Collections.emptyList();

        List<String> objectiveLines = readLines(new File(outDir, "objectives.pf"));
        List<String> timeLines = readLines(new File(outDir, "times.pf"));
        List<String> genLines = readLines(new File(outDir, "generations.pf"));
        String solutionsTxt = readWhole(new File(outDir, "solutions.txt"));
        List<String> solutionSummaries = splitSolutionSummaries(solutionsTxt);

        // Join objectives -> summary and time by solution index.
        Map<Integer, String> idxToObjective = new HashMap<>();
        List<double[]> parsedObjectives = new ArrayList<>();
        for (int i = 0; i < objectiveLines.size(); i++) {
            String ln = objectiveLines.get(i).trim();
            if (!ln.isEmpty()) {
                idxToObjective.put(i, ln);
                parsedObjectives.add(parseDoubles(ln));
            } else {
                parsedObjectives.add(new double[0]);
            }
        }
        Map<Integer, Long> idxToTime = new HashMap<>();
        for (int i = 0; i < timeLines.size(); i++) {
            String ln = timeLines.get(i).trim();
            if (!ln.isEmpty()) {
                try {
                    idxToTime.put(i, Long.parseLong(ln));
                } catch (NumberFormatException ignored) {}
            }
        }
        Map<Integer, Integer> idxToGen = new HashMap<>();
        for (int i = 0; i < genLines.size(); i++) {
            String ln = genLines.get(i).trim();
            if (!ln.isEmpty()) {
                try {
                    idxToGen.put(i, Integer.parseInt(ln));
                } catch (NumberFormatException ignored) {}
            }
        }
        Map<Integer, String> idxToSummary = new HashMap<>();
        for (int i = 0; i < solutionSummaries.size(); i++) {
            String s = solutionSummaries.get(i);
            if (s != null && !s.trim().isEmpty()) idxToSummary.put(i, s.trim());
        }

        // Model files: search models/, solutions/, and outDir directly.
        List<File> modelFilesList = new ArrayList<>();
        File modelsDir = new File(outDir, "models");
        if (modelsDir.exists() && modelsDir.isDirectory()) {
            File[] mfs = modelsDir.listFiles(f -> f != null && f.isFile() && f.getName().toLowerCase().endsWith(".xmi"));
            if (mfs != null) Collections.addAll(modelFilesList, mfs);
        }
        File solutionsDir = new File(outDir, "solutions");
        if (solutionsDir.exists() && solutionsDir.isDirectory()) {
            File[] sfs = solutionsDir.listFiles(f -> f != null && f.isFile() && f.getName().toLowerCase().endsWith(".xmi"));
            if (sfs != null) {
                for (File f : sfs) {
                    if (!modelFilesList.contains(f)) modelFilesList.add(f);
                }
            }
        }
        File[] directFs = outDir.listFiles(f -> f != null && f.isFile() && f.getName().toLowerCase().endsWith(".xmi"));
        if (directFs != null) {
            for (File f : directFs) {
                if (!modelFilesList.contains(f)) modelFilesList.add(f);
            }
        }

        List<SolutionEntry> entries = new ArrayList<>();
        for (int i = 0; i < modelFilesList.size(); i++) {
            File mf = modelFilesList.get(i);
            SolutionEntry e = new SolutionEntry();
            e.outputDir = outDir.getPath();
            e.modelPath = mf.getPath();

            int idx = -1;
            double[] modelObjs = parseDoublesFromFilename(mf.getName());
            if (modelObjs != null && modelObjs.length > 0) {
                for (int j = 0; j < parsedObjectives.size(); j++) {
                    if (doublesEqual(modelObjs, parsedObjectives.get(j))) {
                        idx = j;
                        break;
                    }
                }
            }

            if (idx >= 0) {
                e.objectiveLine = idxToObjective.get(idx);
                e.summary = idxToSummary.get(idx);
                e.timeToFormMs = idxToTime.get(idx);
                e.generationToForm = idxToGen.get(idx);
            } else if (idxToObjective.containsKey(i)) {
                e.objectiveLine = idxToObjective.get(i);
                e.summary = idxToSummary.get(i);
                e.timeToFormMs = idxToTime.get(i);
                e.generationToForm = idxToGen.get(i);
            } else {
                // Fall back: attach first objective/summary if only one exists.
                if (objectiveLines.size() == 1) e.objectiveLine = objectiveLines.get(0).trim();
                if (solutionSummaries.size() == 1) e.summary = solutionSummaries.get(0).trim();
                if (timeLines.size() == 1) e.timeToFormMs = idxToTime.get(0);
                if (genLines.size() == 1) e.generationToForm = idxToGen.get(0);
            }

            if (e.summary == null || e.summary.isBlank()) {
                e.summary = "Solution " + (i + 1) + ": " + mf.getName();
            }

            entries.add(e);
        }

        // Fallback: If no .xmi model files were generated, but objectives or summaries exist
        if (entries.isEmpty() && (!objectiveLines.isEmpty() || !solutionSummaries.isEmpty())) {
            int maxCount = Math.max(objectiveLines.size(), solutionSummaries.size());
            for (int i = 0; i < maxCount; i++) {
                SolutionEntry e = new SolutionEntry();
                e.outputDir = outDir.getPath();
                e.modelPath = outDir.getPath();
                if (i < objectiveLines.size()) e.objectiveLine = objectiveLines.get(i).trim();
                if (i < solutionSummaries.size()) e.summary = solutionSummaries.get(i).trim();
                if (i < timeLines.size()) e.timeToFormMs = idxToTime.get(i);
                if (i < genLines.size()) e.generationToForm = idxToGen.get(i);
                if (e.summary == null || e.summary.isBlank()) {
                    e.summary = "Solution " + (i + 1);
                }
                entries.add(e);
            }
        }

        // Stable order: objectiveLine then filename.
        entries.sort(Comparator
                .comparing((SolutionEntry s) -> s.objectiveLine == null ? "" : s.objectiveLine)
                .thenComparing(s -> s.modelPath == null ? "" : s.modelPath));

        String dirKey = outDir.getAbsolutePath();
        if (!entries.isEmpty()) {
            LAST_VALID_ENTRIES_CACHE.put(dirKey, entries);
            return entries;
        }

        List<SolutionEntry> cached = LAST_VALID_ENTRIES_CACHE.get(dirKey);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        return entries;
    }

    private static double[] parseDoubles(String text) {
        if (text == null || text.trim().isEmpty()) return new double[0];
        String[] parts = text.trim().split("\\s+");
        List<Double> list = new ArrayList<>();
        for (String p : parts) {
            try {
                list.add(Double.parseDouble(p));
            } catch (NumberFormatException ignored) {}
        }
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private static double[] parseDoublesFromFilename(String name) {
        if (name == null) return new double[0];
        String n = name;
        if (n.toLowerCase().endsWith(".xmi")) n = n.substring(0, n.length() - 4);
        String[] parts = n.split("_");
        List<Double> list = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            char first = p.charAt(0);
            if (Character.isDigit(first) || (first == '-' && p.length() > 1) || first == '.') {
                try {
                    list.add(Double.parseDouble(p));
                } catch (NumberFormatException ignored) {}
            }
        }
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private static boolean doublesEqual(double[] a, double[] b) {
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-4) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeObjectiveLine(String ln) {
        if (ln == null) return null;
        String[] parts = ln.trim().split("\\s+");
        List<String> numericParts = new ArrayList<>();
        for (String p : parts) {
            if (!p.isBlank()) numericParts.add(p);
        }
        if (numericParts.isEmpty()) return null;
        return String.join(" ", numericParts);
    }

    private static String objectiveFromModelFilename(String name) {
        if (name == null) return null;
        // Example: blocky_custom_-1.0_4.0_5.0_0.0.xmi -> "-1.0 4.0 5.0 0.0"
        String n = name;
        if (n.toLowerCase().endsWith(".xmi")) n = n.substring(0, n.length() - 4);
        
        String[] parts = n.split("_");
        List<String> numericParts = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            char first = p.charAt(0);
            // Heuristic: objective parts in MOMoT filenames start with a digit, minus, or dot.
            if (Character.isDigit(first) || (first == '-' && p.length() > 1) || first == '.') {
                numericParts.add(p);
            }
        }
        
        if (numericParts.isEmpty()) return null;
        return String.join(" ", numericParts);
    }

    private static List<String> splitSolutionSummaries(String solutionsTxt) {
        if (solutionsTxt == null || solutionsTxt.trim().isEmpty()) return Collections.emptyList();

        String[] lines = solutionsTxt.split("\\r?\\n");
        List<String> out = new ArrayList<>();

        StringBuilder cur = null;
        boolean inSolution = false;
        for (String line : lines) {
            if (line != null && line.startsWith("Solution ")) {
                if (cur != null) out.add(cur.toString().trim());
                cur = new StringBuilder();
                inSolution = true;
                cur.append(line).append("\n");
                continue;
            }
            if (!inSolution) continue;
            if (cur == null) cur = new StringBuilder();

            if (line.startsWith("Number of objectives:")
                    || line.trim().startsWith("GoalReached:")
                    || line.trim().startsWith("SolutionLength:")
                    || line.trim().startsWith("Blocks:")
                    || line.startsWith("AggregatedFitness:")
                    || line.startsWith("Number of constraints:")
                    || line.startsWith("  AggregatedFitness:")
                    || line.startsWith("  GoalReached:")
                    || line.startsWith("  SolutionLength:")
                    || line.startsWith("  Blocks:")) {
                cur.append(line).append("\n");
            }
        }
        if (cur != null) out.add(cur.toString().trim());
        return out;
    }

    private static List<String> readLines(File file) {
        if (file == null || !file.exists() || !file.isFile()) return Collections.emptyList();
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String ln;
            while ((ln = br.readLine()) != null) lines.add(ln);
        } catch (Exception ignored) {
        }
        return lines;
    }

    private static String readWhole(File file) {
        if (file == null || !file.exists() || !file.isFile()) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String ln;
            while ((ln = br.readLine()) != null) {
                sb.append(ln).append("\n");
            }
        } catch (Exception ignored) {
            return null;
        }
        return sb.toString();
    }
}
