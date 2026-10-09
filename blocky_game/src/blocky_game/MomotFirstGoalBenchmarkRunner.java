package blocky_game;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * First-Goal Benchmark Runner for MoMoT across Blockly maze levels 1..10.
 *
 * <p>Measures the time (ms / s) and generation number required to synthesize the first
 * goal-reaching solution ({@code GoalReached <= -0.5}) under early stopping. Evaluates
 * each level across independent runs (default 30) with distinct random seeds.
 */
public final class MomotFirstGoalBenchmarkRunner {

    public static final int[] CANONICAL_MIN_SOLUTION_LENGTHS = { 2, 8, 2, 11, 8, 10, 8, 12, 8, 38 };

    static final int DEFAULT_POPULATION_SIZE = 150;
    static final int DEFAULT_ITERATIONS = 100;
    static final int DEFAULT_RUNS = 30;

    private MomotFirstGoalBenchmarkRunner() {}

    public static void main(String[] args) throws IOException {
        int fromLevel = parseIntProperty("blocky.fromLevel", 1);
        int toLevel = parseIntProperty("blocky.toLevel", 10);
        int popSize = parseIntProperty("blocky.populationSize", DEFAULT_POPULATION_SIZE);
        int iterations = parseIntProperty("blocky.iterations", DEFAULT_ITERATIONS);
        int maxEval = parseIntProperty("blocky.maxEvaluations", popSize * iterations);
        int runs = parseIntProperty("blocky.runs", parseIntProperty("blocky.nrRuns", DEFAULT_RUNS));

        String sessionId = System.getProperty("blocky.benchmarkSession");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + "_first_goal";
        }

        System.out.println("[FirstGoalBenchmark] Session: " + sessionId);
        System.out.println("[FirstGoalBenchmark] Levels: " + fromLevel + ".." + toLevel);
        System.out.println("[FirstGoalBenchmark] Runs per level: " + runs);
        System.out.println("[FirstGoalBenchmark] populationSize=" + popSize
                + " iterations=" + iterations
                + " maxEvaluations=" + maxEval);
        System.out.println("[FirstGoalBenchmark] algorithm=" + System.getProperty("blocky.algorithm", "NSGA_II")
                + " solutionLengthFactor=" + parseIntProperty("blocky.solutionLengthFactor", 1));

        MomotRunService.warmupRunnerClass();

        File analysisDir = resolveAnalysisDir();
        File rawCsvFile = new File(analysisDir, "first_goal_benchmark_raw_" + sessionId + ".csv");
        File summaryCsvFile = new File(analysisDir, "first_goal_benchmark_summary_" + sessionId + ".csv");

        initRawCsvHeader(rawCsvFile);
        initSummaryCsvHeader(summaryCsvFile);

        List<LevelSummary> levelSummaries = new ArrayList<>();

        for (int level = fromLevel; level <= toLevel; level++) {
            LevelSummary summary = processLevel(level, sessionId, popSize, maxEval, runs, rawCsvFile);
            if (summary != null) {
                levelSummaries.add(summary);
                appendSummaryCsvRow(summaryCsvFile, summary, popSize, maxEval);
            }
        }

        System.out.println("\n=======================================================");
        System.out.println("First-Goal Benchmark Execution Completed");
        System.out.println("Raw CSV:     " + rawCsvFile.getAbsolutePath());
        System.out.println("Summary CSV: " + summaryCsvFile.getAbsolutePath());
        System.out.println("=======================================================");
    }

    private static void initRawCsvHeader(File file) throws IOException {
        try (FileWriter fw = new FileWriter(file, StandardCharsets.UTF_8, false)) {
            fw.write("level,runIndex,seed,inputXmi,henshinModule,solutionLength,populationSize,maxEvaluations,"
                    + "solved,timeToFirstGoalMs,timeToFirstGoalSec,generationOfFirstGoal,evaluationsAtFirstGoal,wallTimeMs,wallTimeSec,outputDir\n");
        }
    }

    private static void initSummaryCsvHeader(File file) throws IOException {
        try (FileWriter fw = new FileWriter(file, StandardCharsets.UTF_8, false)) {
            fw.write("level,inputXmi,henshinModule,solutionLength,totalRuns,successCount,successRate,"
                    + "meanTimeMs,stdDevTimeMs,medianTimeMs,minTimeMs,maxTimeMs,q1TimeMs,q3TimeMs,iqrTimeMs,"
                    + "meanTimeSec,stdDevTimeSec,medianTimeSec,minTimeSec,maxTimeSec,q1TimeSec,q3TimeSec,iqrTimeSec,"
                    + "meanGen,stdDevGen,medianGen,minGen,maxGen,q1Gen,q3Gen,iqrGen,"
                    + "meanFailedTimeSec,ettSec,eet,"
                    + "populationSize,maxEvaluations\n");
        }
    }

    private static LevelSummary processLevel(int level, String sessionId, int popSize, int maxEval, int totalRuns, File rawCsvFile) throws IOException {
        System.out.println("\n=======================================================");
        System.out.println("[FirstGoalBenchmark] Level " + level + " START");
        System.out.println("=======================================================");

        String inputPath = resolveFirstExisting(
                "blocky_momot/model/input/" + level + ".xmi",
                "../blocky_momot/model/input/" + level + ".xmi",
                "model/" + level + ".xmi");

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.err.println("[FirstGoalBenchmark] FAILED: input model not found for level " + level);
            return null;
        }

        String absInput = inputFile.getAbsolutePath();
        String henshinName = selectHenshinModuleForLevel(level);
        String henshinPath = "../blocky_model/transformations/" + henshinName;
        System.setProperty("blocky.henshin", henshinPath);

        // blocky.solutionLengthFactor (default 1) scales the canonical length, e.g. 2 for twice as long.
        int solLen = canonicalSolutionLengthForLevel(level) * parseIntProperty("blocky.solutionLengthFactor", 1);

        List<RunResult> runResults = new ArrayList<>();

        for (int runIdx = 1; runIdx <= totalRuns; runIdx++) {
            int seed = runIdx;
            System.out.println("[FirstGoalBenchmark] Level " + level + " Run " + runIdx + "/" + totalRuns + " (seed=" + seed + ")...");

            RunResult res = executeSingleRun(absInput, henshinName, level, runIdx, seed, solLen, popSize, maxEval, sessionId);
            runResults.add(res);
            appendRawCsvRow(rawCsvFile, res);

            if (res.solved) {
                System.out.println(String.format(Locale.US,
                        "[FirstGoalBenchmark] Level %d Run %d SOLVED: time=%.3fs (%d ms), gen=%d",
                        level, runIdx, res.timeToGoalSec, res.timeToGoalMs, res.genToGoal));
            } else {
                System.out.println("[FirstGoalBenchmark] Level " + level + " Run " + runIdx + " UNSOLVED (wallTime="
                        + String.format(Locale.US, "%.3fs", res.wallTimeSec) + ")");
            }
        }

        LevelSummary summary = calculateSummary(level, absInput, henshinName, solLen, totalRuns, runResults, popSize, maxEval);
        System.out.println(String.format(Locale.US,
                "[FirstGoalBenchmark] Level %d SUMMARY: SuccessRate=%.1f%% (%d/%d), MeanTime=%.3fs, MedianTime=%.3fs, MeanGen=%.1f, ETT=%.3fs, EET=%.1f",
                level, summary.successRate * 100.0, summary.successCount, totalRuns, summary.timeSecStats.mean, summary.timeSecStats.median, summary.genStats.mean,
                summary.ettSec, summary.eet));

        return summary;
    }

    private static RunResult executeSingleRun(String inputXmi, String henshinModule, int level, int runIdx, int seed,
            int solutionLength, int popSize, int maxEval, String sessionId) {

        String outBase = "blocky_momot/output_fg_" + sessionId + "_lvl" + level + "_run" + runIdx;

        System.setProperty("blocky.henshin", "../blocky_model/transformations/" + henshinModule);
        System.setProperty("blocky.stopOnFirstGoal", "true");
        System.setProperty("blocky.seed", String.valueOf(seed));

        // Force seed for PRNG
        org.moeaframework.core.PRNG.setSeed(seed);

        MomotRunService.RunSpec spec = new MomotRunService.RunSpec(
                inputXmi, outBase, popSize, maxEval, 1, solutionLength, true);

        long startWallMs = System.currentTimeMillis();
        String outDirStr = MomotRunService.runSync(spec, null, null);
        long elapsedWallMs = Math.max(1, System.currentTimeMillis() - startWallMs);
        double wallSec = elapsedWallMs / 1000.0;

        if (outDirStr == null) {
            return new RunResult(level, runIdx, seed, inputXmi, henshinModule, solutionLength, popSize, maxEval,
                    false, null, null, null, null, elapsedWallMs, wallSec, "");
        }

        File outDir = new File(outDirStr);
        File firstGoalFile = new File(outDir, "first_goal.txt");
        File objectivesPf = new File(outDir, "objectives.pf");

        boolean solved = hasGoalReachedSolution(objectivesPf);

        Long timeMs = null;
        Integer gen = null;
        Integer evals = null;

        if (solved) {
            if (firstGoalFile.exists() && firstGoalFile.isFile()) {
                ParsedFirstGoal fg = parseFirstGoalFile(firstGoalFile);
                if (fg != null) {
                    timeMs = fg.timeToGoalMs;
                    gen = fg.generationOfGoal;
                }
            }

            if (timeMs == null || gen == null) {
                timeMs = elapsedWallMs;
                gen = parseGenFromFiles(outDir);
            }

            if (gen == null || gen <= 0) {
                gen = 1;
            }
            evals = gen * popSize;
        }

        Double timeSec = timeMs != null ? timeMs / 1000.0 : null;

        return new RunResult(level, runIdx, seed, inputXmi, henshinModule, solutionLength, popSize, maxEval,
                solved, timeMs, timeSec, gen, evals, elapsedWallMs, wallSec, outDir.getAbsolutePath());
    }

    private static ParsedFirstGoal parseFirstGoalFile(File file) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            Long timeMs = null;
            Integer gen = null;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("timeToFirstGoalMs=")) {
                    timeMs = Long.parseLong(line.substring("timeToFirstGoalMs=".length()).trim());
                } else if (line.startsWith("generationOfFirstGoal=")) {
                    gen = Integer.parseInt(line.substring("generationOfFirstGoal=".length()).trim());
                }
            }
            if (timeMs != null && gen != null) {
                return new ParsedFirstGoal(timeMs, gen);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Integer parseGenFromFiles(File outDir) {
        File gensFile = new File(outDir, "generations.pf");
        if (gensFile.exists() && gensFile.isFile()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(gensFile), StandardCharsets.UTF_8))) {
                String line = br.readLine();
                if (line != null && !line.isBlank()) {
                    return Integer.parseInt(line.trim());
                }
            } catch (Exception ignored) {
            }
        }
        return 1;
    }

    private static boolean hasGoalReachedSolution(File objectivesPf) {
        if (objectivesPf == null || !objectivesPf.exists() || !objectivesPf.isFile()) {
            return false;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(objectivesPf), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 1) continue;
                double goalVal = Double.parseDouble(parts[0]);
                if (goalVal <= -0.5) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static void appendRawCsvRow(File file, RunResult r) throws IOException {
        try (FileWriter fw = new FileWriter(file, StandardCharsets.UTF_8, true)) {
            fw.write(String.format(Locale.US,
                    "%d,%d,%d,%s,%s,%d,%d,%d,%b,%s,%s,%s,%s,%d,%.3f,%s\n",
                    r.level, r.runIdx, r.seed, escapeCsv(r.inputXmi), escapeCsv(r.henshinModule),
                    r.solutionLength, r.populationSize, r.maxEvaluations, r.solved,
                    r.timeToGoalMs != null ? String.valueOf(r.timeToGoalMs) : "",
                    r.timeToGoalSec != null ? String.format(Locale.US, "%.3f", r.timeToGoalSec) : "",
                    r.genToGoal != null ? String.valueOf(r.genToGoal) : "",
                    r.evalsToGoal != null ? String.valueOf(r.evalsToGoal) : "",
                    r.wallTimeMs, r.wallTimeSec,
                    escapeCsv(r.outputDir)));
        }
    }

    private static void appendSummaryCsvRow(File file, LevelSummary s, int popSize, int maxEval) throws IOException {
        try (FileWriter fw = new FileWriter(file, StandardCharsets.UTF_8, true)) {
            fw.write(String.format(Locale.US,
                    "%d,%s,%s,%d,%d,%d,%.4f,"
                    + "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,"
                    + "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,"
                    + "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,"
                    + "%.4f,%.4f,%.4f,"
                    + "%d,%d\n",
                    s.level, escapeCsv(s.inputXmi), escapeCsv(s.henshinModule), s.solutionLength,
                    s.totalRuns, s.successCount, s.successRate,
                    s.timeMsStats.mean, s.timeMsStats.stdDev, s.timeMsStats.median, s.timeMsStats.min, s.timeMsStats.max, s.timeMsStats.q1, s.timeMsStats.q3, s.timeMsStats.iqr,
                    s.timeSecStats.mean, s.timeSecStats.stdDev, s.timeSecStats.median, s.timeSecStats.min, s.timeSecStats.max, s.timeSecStats.q1, s.timeSecStats.q3, s.timeSecStats.iqr,
                    s.genStats.mean, s.genStats.stdDev, s.genStats.median, s.genStats.min, s.genStats.max, s.genStats.q1, s.genStats.q3, s.genStats.iqr,
                    s.meanFailedTimeSec, s.ettSec, s.eet,
                    popSize, maxEval));
        }
    }

    private static LevelSummary calculateSummary(int level, String inputXmi, String henshinModule, int solutionLength,
            int totalRuns, List<RunResult> runResults, int popSize, int maxEval) {

        List<Double> timeMsList = new ArrayList<>();
        List<Double> timeSecList = new ArrayList<>();
        List<Double> genList = new ArrayList<>();
        List<Double> failedWallSecList = new ArrayList<>();
        int successCount = 0;

        for (RunResult r : runResults) {
            if (r.solved && r.timeToGoalMs != null && r.genToGoal != null) {
                successCount++;
                timeMsList.add((double) r.timeToGoalMs);
                timeSecList.add(r.timeToGoalSec);
                genList.add((double) r.genToGoal);
            } else {
                if (r.wallTimeSec != null) {
                    failedWallSecList.add(r.wallTimeSec);
                }
            }
        }

        double successRate = totalRuns > 0 ? (double) successCount / totalRuns : 0.0;

        Stats timeMsStats = computeStats(timeMsList);
        Stats timeSecStats = computeStats(timeSecList);
        Stats genStats = computeStats(genList);

        double meanFailedTimeSec = 0.0;
        if (!failedWallSecList.isEmpty()) {
            double sumFailedSec = 0.0;
            for (double fSec : failedWallSecList) {
                sumFailedSec += fSec;
            }
            meanFailedTimeSec = sumFailedSec / failedWallSecList.size();
        }

        double ettSec;
        double eet;

        if (successCount == 0) {
            ettSec = Double.POSITIVE_INFINITY;
            eet = Double.POSITIVE_INFINITY;
        } else {
            double failureFactor = (1.0 - successRate) / successRate;
            ettSec = failureFactor * meanFailedTimeSec + timeSecStats.mean;
            eet = failureFactor * maxEval + genStats.mean * popSize;
        }

        return new LevelSummary(level, inputXmi, henshinModule, solutionLength, totalRuns, successCount, successRate,
                timeMsStats, timeSecStats, genStats, meanFailedTimeSec, ettSec, eet);
    }

    private static Stats computeStats(List<Double> data) {
        if (data == null || data.isEmpty()) {
            return new Stats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        List<Double> sorted = new ArrayList<>(data);
        Collections.sort(sorted);
        int n = sorted.size();

        double sum = 0.0;
        for (double v : sorted) {
            sum += v;
        }
        double mean = sum / n;

        double variance = 0.0;
        if (n > 1) {
            double sqDiffSum = 0.0;
            for (double v : sorted) {
                double diff = v - mean;
                sqDiffSum += diff * diff;
            }
            variance = sqDiffSum / (n - 1);
        }
        double stdDev = Math.sqrt(variance);

        double min = sorted.get(0);
        double max = sorted.get(n - 1);
        double median = percentile(sorted, 0.50);
        double q1 = percentile(sorted, 0.25);
        double q3 = percentile(sorted, 0.75);
        double iqr = q3 - q1;

        return new Stats(mean, stdDev, median, min, max, q1, q3, iqr);
    }

    private static double percentile(List<Double> sorted, double p) {
        int n = sorted.size();
        if (n == 0) return 0.0;
        if (n == 1) return sorted.get(0);
        double rank = p * (n - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) return sorted.get(lower);
        double fraction = rank - lower;
        return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
    }

    public static String selectHenshinModuleForLevel(int level) {
        if (Boolean.getBoolean("blocky.rules.levelSpecific")) {
            if (level <= 2) {
                return "statement_insertions_atomic_only_edit_anywhere.henshin";
            } else if (level <= 5) {
                return withWrapMoves("statement_insertions_no_conds_edit_anywhere.henshin");
            } else if (level <= 7) {
                return withWrapMoves("statement_insertions_no_else_edit_anywhere.henshin");
            }
        }
        return withWrapMoves("statement_insertions_henshin_text_edit_anywhere.henshin");
    }

    public static String defaultHenshinModule() {
        return withWrapMoves("statement_insertions_henshin_text_edit_anywhere.henshin");
    }

    /**
     * PROTOTYPE: with -Dblocky.rules.wrap=true the *_wrap.henshin variant is used (same rules plus wrap/unwrap
     * moves, built by tools/henshin-prototype/run.sh wrap). There is none for atomic_only.
     */
    public static String withWrapMoves(String henshinFile) {
        // blocky.rules.editAnywhere=true: the *_edit_anywhere variant (insert, delete, modify as one move), so
        // that wrap off and wrap on differ only in the wrap/unwrap moves
        if (Boolean.getBoolean("blocky.rules.editAnywhere") && !henshinFile.contains("atomic_only") && !henshinFile.contains("edit_anywhere")) {
            henshinFile = henshinFile.replaceFirst("\\.henshin$", "_edit_anywhere.henshin");
        }
        if (!Boolean.getBoolean("blocky.rules.wrap") || henshinFile.contains("atomic_only") || henshinFile.contains("_wrap")) {
            return henshinFile;
        }
        return henshinFile.replaceFirst("\\.henshin$", "_wrap.henshin");
    }

    public static int canonicalSolutionLengthForLevel(int level) {
        if (level >= 1 && level <= CANONICAL_MIN_SOLUTION_LENGTHS.length) {
            return CANONICAL_MIN_SOLUTION_LENGTHS[level - 1];
        }
        return 10;
    }

    private static File resolveAnalysisDir() {
        String prop = System.getProperty("blocky.analysisDir");
        if (prop != null && !prop.isBlank()) {
            File f = new File(prop);
            if (f.exists() || f.mkdirs()) return f.getAbsoluteFile();
        }
        File currentDir = new File(System.getProperty("user.dir"));
        if ("blocky_game".equals(currentDir.getName())) {
            File target = new File(currentDir.getParentFile(), "blocky_momot/analysis");
            if (target.exists() || target.mkdirs()) return target.getAbsoluteFile();
        }
        File target = new File(currentDir, "blocky_momot/analysis");
        if (target.exists() || target.mkdirs()) return target.getAbsoluteFile();
        return target.getAbsoluteFile();
    }

    private static int parseIntProperty(String key, int fallback) {
        try {
            String raw = System.getProperty(key);
            if (raw != null && !raw.isBlank()) {
                return Integer.parseInt(raw.trim());
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static String resolveFirstExisting(String... candidates) {
        if (candidates == null || candidates.length == 0) {
            return "model/1.xmi";
        }
        for (String c : candidates) {
            if (c == null || c.isBlank()) {
                continue;
            }
            File f = new File(c);
            if (f.exists() && f.isFile()) {
                return c;
            }
        }
        return candidates[0];
    }

    private static String escapeCsv(String val) {
        if (val == null) return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }

    static class ParsedFirstGoal {
        final long timeToGoalMs;
        final int generationOfGoal;

        ParsedFirstGoal(long timeToGoalMs, int generationOfGoal) {
            this.timeToGoalMs = timeToGoalMs;
            this.generationOfGoal = generationOfGoal;
        }
    }

    static class RunResult {
        final int level;
        final int runIdx;
        final int seed;
        final String inputXmi;
        final String henshinModule;
        final int solutionLength;
        final int populationSize;
        final int maxEvaluations;
        final boolean solved;
        final Long timeToGoalMs;
        final Double timeToGoalSec;
        final Integer genToGoal;
        final Integer evalsToGoal;
        final long wallTimeMs;
        final Double wallTimeSec;
        final String outputDir;

        RunResult(int level, int runIdx, int seed, String inputXmi, String henshinModule, int solutionLength,
                int populationSize, int maxEvaluations, boolean solved, Long timeToGoalMs, Double timeToGoalSec,
                Integer genToGoal, Integer evalsToGoal, long wallTimeMs, Double wallTimeSec, String outputDir) {
            this.level = level;
            this.runIdx = runIdx;
            this.seed = seed;
            this.inputXmi = inputXmi;
            this.henshinModule = henshinModule;
            this.solutionLength = solutionLength;
            this.populationSize = populationSize;
            this.maxEvaluations = maxEvaluations;
            this.solved = solved;
            this.timeToGoalMs = timeToGoalMs;
            this.timeToGoalSec = timeToGoalSec;
            this.genToGoal = genToGoal;
            this.evalsToGoal = evalsToGoal;
            this.wallTimeMs = wallTimeMs;
            this.wallTimeSec = wallTimeSec;
            this.outputDir = outputDir;
        }
    }

    static class Stats {
        final double mean;
        final double stdDev;
        final double median;
        final double min;
        final double max;
        final double q1;
        final double q3;
        final double iqr;

        Stats(double mean, double stdDev, double median, double min, double max, double q1, double q3, double iqr) {
            this.mean = mean;
            this.stdDev = stdDev;
            this.median = median;
            this.min = min;
            this.max = max;
            this.q1 = q1;
            this.q3 = q3;
            this.iqr = iqr;
        }
    }

    static class LevelSummary {
        final int level;
        final String inputXmi;
        final String henshinModule;
        final int solutionLength;
        final int totalRuns;
        final int successCount;
        final double successRate;
        final Stats timeMsStats;
        final Stats timeSecStats;
        final Stats genStats;
        final double meanFailedTimeSec;
        final double ettSec;
        final double eet;

        LevelSummary(int level, String inputXmi, String henshinModule, int solutionLength, int totalRuns, int successCount,
                double successRate, Stats timeMsStats, Stats timeSecStats, Stats genStats,
                double meanFailedTimeSec, double ettSec, double eet) {
            this.level = level;
            this.inputXmi = inputXmi;
            this.henshinModule = henshinModule;
            this.solutionLength = solutionLength;
            this.totalRuns = totalRuns;
            this.successCount = successCount;
            this.successRate = successRate;
            this.timeMsStats = timeMsStats;
            this.timeSecStats = timeSecStats;
            this.genStats = genStats;
            this.meanFailedTimeSec = meanFailedTimeSec;
            this.ettSec = ettSec;
            this.eet = eet;
        }
    }
}
