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
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * CLI runner that estimates, per level, the statistically supported minimum MOMoT
 * {@code solutionLength} and the expected minimum length under a Bernoulli success model.
 *
 * <p>Supports checkpoint/resume via {@code -Dblocky.resume=true} and optional
 * {@code -Dblocky.benchmarkSession=<timestamp>}. Progress is stored under
 * {@code blocky_momot/analysis/checkpoints/<session>/}.
 */
public final class MomotMinSolutionLengthRunner {

    private static final int DEFAULT_PARALLEL_LEVELS = 1;
    private static final int DEFAULT_NR_RUNS = 30;
    private static final double DEFAULT_MIN_SUCCESS_RATE = 0.8;
    private static final double DEFAULT_CONFIDENCE_LEVEL = 0.95;

    /** Known optimal Blockly block count per maze level (1..10). */
    private static final int[] OPTIMAL_BLOCK_COUNT = { 2, 5, 2, 5, 5, 4, 4, 5, 4, 7 };

    private MomotMinSolutionLengthRunner() {}

    public static void main(String[] args) throws IOException {
        double minSuccessRate = parseDoubleProperty("blocky.minSuccessRate", DEFAULT_MIN_SUCCESS_RATE);
        double confidenceLevel = parseDoubleProperty("blocky.confidenceLevel", DEFAULT_CONFIDENCE_LEVEL);

        String recoverLog = System.getProperty("blocky.recoverLog");
        if (recoverLog != null && !recoverLog.isBlank()) {
            String sessionId = System.getProperty("blocky.benchmarkSession", "20260616_134746");
            BenchmarkCheckpoint.importFromRunLog(new File(recoverLog), sessionId, minSuccessRate, confidenceLevel);
            return;
        }

        boolean resume = Boolean.parseBoolean(System.getProperty("blocky.resume", "false"));
        String sessionId = System.getProperty("blocky.benchmarkSession");
        if (resume && (sessionId == null || sessionId.isBlank())) {
            sessionId = BenchmarkCheckpoint.findLatestSessionId();
        }
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        }

        String userPopSize = System.getProperty("blocky.populationSize");
        String userMaxEval = System.getProperty("blocky.maxEvaluations");
        String userNrRuns = System.getProperty("blocky.nrRuns");

        System.clearProperty("blocky.seed");
        MomotRunService.warmupRunnerClass();

        BenchmarkCheckpoint checkpoint = new BenchmarkCheckpoint(sessionId);
        if (!resume) {
            Properties session = new Properties();
            session.setProperty("sessionId", sessionId);
            session.setProperty("minSuccessRate", String.valueOf(minSuccessRate));
            session.setProperty("confidenceLevel", String.valueOf(confidenceLevel));
            session.setProperty("nrRuns", userNrRuns != null ? userNrRuns : String.valueOf(DEFAULT_NR_RUNS));
            checkpoint.saveSession(session);
        }

        System.out.println("[MinSolutionLength] Session: " + sessionId + (resume ? " (RESUME)" : " (NEW)"));

        int parallelLevels = resolveParallelLevels();
        if (parallelLevels > 1) {
            System.out.println("[MinSolutionLength] NOTE: MOMoT/Henshin is not thread-safe; "
                    + "searches run one at a time even with blocky.parallelLevels=" + parallelLevels + ".");
        }

        ExecutorService executor = Executors.newFixedThreadPool(parallelLevels);
        List<Future<LevelResultSnapshot>> futures = new ArrayList<>();

        final String runSessionId = sessionId;
        final boolean runResume = resume;
        final BenchmarkCheckpoint runCheckpoint = checkpoint;

        for (int level = 1; level <= 10; level++) {
            if (runResume && runCheckpoint.isLevelDone(level)) {
                System.out.println("[MinSolutionLength] Level " + level + " SKIP (already in checkpoint)");
                continue;
            }
            final int lv = level;
            futures.add(executor.submit(() -> processLevel(
                    lv, runSessionId, runCheckpoint, userPopSize, userMaxEval, userNrRuns,
                    minSuccessRate, confidenceLevel, runResume)));
        }

        List<LevelResultSnapshot> results = new ArrayList<>();
        for (int level = 1; level <= 10; level++) {
            if (checkpoint.isLevelDone(level)) {
                LevelResultSnapshot done = checkpoint.loadLevelDone(level);
                if (done != null) {
                    results.add(done);
                }
            }
        }
        for (Future<LevelResultSnapshot> future : futures) {
            try {
                LevelResultSnapshot row = future.get();
                if (row != null) {
                    results.add(row);
                }
            } catch (Exception e) {
                System.err.println("[MinSolutionLength] Level task failed: " + e.getMessage());
                e.printStackTrace();
            }
        }

        executor.shutdown();
        results.sort(Comparator.comparingInt(r -> r.level));
        writeOutputs(sessionId, results, minSuccessRate, confidenceLevel);
        checkpoint.writeMergedOutputs(minSuccessRate, confidenceLevel);
    }

    private static LevelResultSnapshot processLevel(int level, String sessionId, BenchmarkCheckpoint checkpoint,
            String userPopSize, String userMaxEval, String userNrRuns, double minSuccessRate,
            double confidenceLevel, boolean resume) {
        try {
            System.out.println("=======================================================");
            System.out.println("[MinSolutionLength] Level " + level + " START");
            System.out.println("=======================================================");

            String input = resolveFirstExisting(
                    "blocky_momot/model/input/" + level + ".xmi",
                    "../blocky_momot/model/input/" + level + ".xmi",
                    "model/" + level + ".xmi"
            );

            File inputFile = new File(input);
            if (!inputFile.exists()) {
                System.err.println("[MinSolutionLength] FAILED: input file not found for level " + level);
                return null;
            }

            String absInput = inputFile.getAbsolutePath();
            int maxBlocks = parseMaxBlocksOrDefault(inputFile, 5);

            int popSize = (userPopSize != null && !userPopSize.isBlank()) ? Integer.parseInt(userPopSize.trim()) : 100;
            int maxEval = (userMaxEval != null && !userMaxEval.isBlank())
                    ? Integer.parseInt(userMaxEval.trim()) : evaluationsForMaxBlocks(maxBlocks);
            int nrRuns = (userNrRuns != null && !userNrRuns.isBlank())
                    ? Integer.parseInt(userNrRuns.trim()) : DEFAULT_NR_RUNS;

            int optimalBlocks = optimalBlockCountForLevel(level);
            int startLen = Math.max(1, optimalBlocks);
            int maxLenCap = Math.max(startLen + 25, maxBlocks * 4);

            List<LengthTrialSnapshot> trials = resume ? checkpoint.loadTrials(level) : new ArrayList<>();
            int attemptLen = resume ? checkpoint.loadNextSolutionLength(level, startLen) : startLen;
            int searchAttempts = trials.size();

            System.out.println("[MinSolutionLength] Configuration for level " + level + ":");
            System.out.println("  sessionId: " + sessionId);
            System.out.println("  inputXmi: " + absInput);
            System.out.println("  resumeFromSolutionLength: " + attemptLen);
            System.out.println("  priorTrials: " + trials.size());
            System.out.println("  nrRuns: " + nrRuns);

            Integer confidentMin = null;
            LengthTrialSnapshot lastTrial = trials.isEmpty() ? null : trials.get(trials.size() - 1);
            int maxLenTried = trials.isEmpty() ? 0 : trials.get(trials.size() - 1).solutionLength;

            while (attemptLen <= maxLenCap) {
                maxLenTried = attemptLen;
                searchAttempts++;
                checkpoint.saveProgress(level, attemptLen);

                System.out.println("[MinSolutionLength] Level " + level + " trying solutionLength=" + attemptLen
                        + " (search attempt #" + searchAttempts + ", " + nrRuns + " independent runs)...");
                LengthTrialSnapshot trial = runTrial(absInput, attemptLen, level, sessionId, searchAttempts,
                        popSize, maxEval, nrRuns, confidenceLevel);
                trials.add(trial);
                lastTrial = trial;
                checkpoint.appendTrial(level, trial);

                System.out.println("[MinSolutionLength] Level " + level + " length=" + attemptLen
                        + " successes=" + trial.successes + "/" + trial.trials
                        + " rate=" + formatRate(trial.successRate)
                        + " wilsonCI=[" + formatRate(trial.ciLow) + ", " + formatRate(trial.ciHigh) + "]");

                if (trial.ciLow >= minSuccessRate) {
                    confidentMin = attemptLen;
                    System.out.println("[MinSolutionLength] Level " + level + " CONFIDENT at solutionLength=" + attemptLen);
                    break;
                }
                attemptLen++;
            }

            double expectedMin = computeExpectedMinimum(trials);
            String status = confidentMin != null ? "SOLVED" : "UNSOLVED";

            LevelResultSnapshot result = LevelResultSnapshot.fromTrials(
                    level, absInput, optimalBlocks, confidentMin, expectedMin,
                    searchAttempts, nrRuns, status, trials);

            checkpoint.saveLevelDone(result);

            System.out.println("=======================================================");
            System.out.println("[MinSolutionLength] Level " + level + " FINISHED: status=" + status
                    + " confidentMin=" + (confidentMin != null ? confidentMin : "")
                    + " expectedMin=" + (Double.isNaN(expectedMin) ? "" : String.format("%.2f", expectedMin)));
            System.out.println("=======================================================");

            return result;
        } catch (IOException e) {
            System.err.println("[MinSolutionLength] Level " + level + " checkpoint error: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static double computeExpectedMinimum(List<LengthTrialSnapshot> trials) {
        if (trials.isEmpty()) {
            return Double.NaN;
        }
        int[] lengths = new int[trials.size()];
        double[] rates = new double[trials.size()];
        for (int i = 0; i < trials.size(); i++) {
            lengths[i] = trials.get(i).solutionLength;
            rates[i] = trials.get(i).successRate;
        }
        return BinomialStats.expectedMinimumLength(lengths, rates);
    }

    private static void writeOutputs(String sessionId, List<LevelResultSnapshot> results,
            double minSuccessRate, double confidenceLevel) {
        File analysisDir = resolveAnalysisDir();
        File summaryCsv = new File(analysisDir, "min_solution_length_" + sessionId + ".csv");
        File trialsCsv = new File(analysisDir, "solution_length_trials_" + sessionId + ".csv");

        try (FileWriter summary = new FileWriter(summaryCsv, StandardCharsets.UTF_8);
             FileWriter trials = new FileWriter(trialsCsv, StandardCharsets.UTF_8)) {
            summary.write("level,inputXmi,optimalBlockCount,expectedMinSolutionLength,confidentMinSolutionLength,"
                    + "successRate,ciLow,ciHigh,nrRunsPerLength,lengthsTried,status,minSuccessRate,confidenceLevel\n");
            trials.write("level,solutionLength,successes,trials,successRate,ciLow,ciHigh\n");

            for (LevelResultSnapshot r : results) {
                summary.write(r.toSummaryCsvRow() + "," + minSuccessRate + "," + confidenceLevel + "\n");
                for (LengthTrialSnapshot t : r.trials) {
                    trials.write(r.level + "," + t.toTrialsCsvRow());
                }
            }

            System.out.println("[MinSolutionLength] Exported summary to: " + summaryCsv.getAbsolutePath());
            System.out.println("[MinSolutionLength] Exported trials to: " + trialsCsv.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("[MinSolutionLength] Error writing CSV files: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static LengthTrialSnapshot runTrial(String inputXmi, int solutionLength, int level, String sessionId,
            int runId, int popSize, int maxEval, int nrRuns, double confidenceLevel) {
        String outBase = "blocky_momot/output_min_len_" + sessionId + "_lvl" + level + "_len" + solutionLength + "_run" + runId;

        MomotRunService.RunSpec spec = new MomotRunService.RunSpec(
                inputXmi, outBase, popSize, maxEval, nrRuns, solutionLength);

        String outDir = MomotRunService.runSync(spec, line -> System.out.println("[L" + level + "] " + line), null);
        int successes = 0;
        int trials = 0;

        if (outDir != null) {
            File dir = new File(outDir);
            File[] perRun = dir.listFiles((d, name) -> name.startsWith("objectives_seed_") && name.endsWith(".pf"));
            if (perRun != null && perRun.length > 0) {
                for (File seedFile : perRun) {
                    trials++;
                    if (hasGoalReachedSolution(seedFile)) {
                        successes++;
                    }
                }
            } else {
                trials = 1;
                if (hasGoalReachedSolution(new File(outDir, "objectives.pf"))) {
                    successes = 1;
                }
            }
        }

        BinomialStats.Estimate estimate = BinomialStats.estimate(successes, trials, confidenceLevel);
        return new LengthTrialSnapshot(solutionLength, estimate.successes, estimate.trials,
                estimate.rate, estimate.ciLow, estimate.ciHigh);
    }

    private static boolean hasGoalReachedSolution(File objectivesPf) {
        if (objectivesPf == null || !objectivesPf.exists() || !objectivesPf.isFile()) return false;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(objectivesPf), StandardCharsets.UTF_8))) {
            String ln;
            while ((ln = br.readLine()) != null) {
                ln = ln.trim();
                if (ln.isEmpty()) continue;
                String[] parts = ln.split("\\s+");
                if (parts.length < 1) continue;
                double goalReached = Double.parseDouble(parts[0]);
                if (goalReached <= -0.999999) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static int resolveParallelLevels() {
        String raw = System.getProperty("blocky.parallelLevels");
        if (raw != null && !raw.isBlank()) {
            try {
                return Math.max(1, Math.min(10, Integer.parseInt(raw.trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_PARALLEL_LEVELS;
    }

    private static File resolveAnalysisDir() {
        File f1 = new File("blocky_momot/analysis");
        File f2 = new File("../blocky_momot/analysis");
        if (f1.exists() || f1.mkdirs()) return f1;
        if (f2.exists() || f2.mkdirs()) return f2;
        return f1;
    }

    private static double parseDoubleProperty(String key, double fallback) {
        try {
            String raw = System.getProperty(key);
            if (raw != null && !raw.isBlank()) {
                return Double.parseDouble(raw.trim());
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static String formatRate(double v) {
        return String.format("%.3f", v);
    }

    private static String resolveFirstExisting(String... candidates) {
        if (candidates == null || candidates.length == 0) return "model/1.xmi";
        for (String c : candidates) {
            if (c == null || c.isBlank()) continue;
            File f = new File(c);
            if (f.exists() && f.isFile()) return c;
        }
        return candidates[0];
    }

    private static int parseMaxBlocksOrDefault(File xmi, int fallback) {
        if (xmi == null || !xmi.exists() || !xmi.isFile()) return fallback;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(xmi), StandardCharsets.UTF_8))) {
            String ln;
            while ((ln = br.readLine()) != null) {
                int idx = ln.indexOf("maxBlocks=\"");
                if (idx < 0) continue;
                int start = idx + "maxBlocks=\"".length();
                int end = ln.indexOf('"', start);
                if (end <= start) continue;
                int v = Integer.parseInt(ln.substring(start, end).trim());
                return v > 0 ? v : fallback;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static int evaluationsForMaxBlocks(int maxBlocks) {
        if (maxBlocks <= 2) return 10_000;
        if (maxBlocks <= 5) return 20_000;
        if (maxBlocks <= 7) return 30_000;
        return 50_000;
    }

    private static int optimalBlockCountForLevel(int level) {
        if (level >= 1 && level <= OPTIMAL_BLOCK_COUNT.length) {
            return OPTIMAL_BLOCK_COUNT[level - 1];
        }
        return 5;
    }

    public static final class LengthTrialSnapshot {
        public final int solutionLength;
        public final int successes;
        public final int trials;
        public final double successRate;
        public final double ciLow;
        public final double ciHigh;

        public LengthTrialSnapshot(int solutionLength, int successes, int trials,
                double successRate, double ciLow, double ciHigh) {
            this.solutionLength = solutionLength;
            this.successes = successes;
            this.trials = trials;
            this.successRate = successRate;
            this.ciLow = ciLow;
            this.ciHigh = ciHigh;
        }

        static LengthTrialSnapshot fromTrialsCsv(String line) {
            String[] p = line.split(",");
            return new LengthTrialSnapshot(
                    Integer.parseInt(p[0].trim()),
                    Integer.parseInt(p[1].trim()),
                    Integer.parseInt(p[2].trim()),
                    Double.parseDouble(p[3].trim()),
                    Double.parseDouble(p[4].trim()),
                    Double.parseDouble(p[5].trim()));
        }

        String toTrialsCsvRow() {
            return solutionLength + "," + successes + "," + trials + ","
                    + String.format("%.4f", successRate) + ","
                    + String.format("%.4f", ciLow) + ","
                    + String.format("%.4f", ciHigh) + "\n";
        }
    }

    public static final class LevelResultSnapshot {
        public final int level;
        public final String inputXmi;
        public final int optimalBlockCount;
        public final Integer confidentMin;
        public final double expectedMin;
        public final double successRate;
        public final double ciLow;
        public final double ciHigh;
        public final int nrRunsPerLength;
        public final int searchAttempts;
        public final String status;
        public final List<LengthTrialSnapshot> trials;

        LevelResultSnapshot(int level, String inputXmi, int optimalBlockCount, Integer confidentMin, double expectedMin,
                double successRate, double ciLow, double ciHigh, int nrRunsPerLength, int searchAttempts,
                String status, List<LengthTrialSnapshot> trials) {
            this.level = level;
            this.inputXmi = inputXmi;
            this.optimalBlockCount = optimalBlockCount;
            this.confidentMin = confidentMin;
            this.expectedMin = expectedMin;
            this.successRate = successRate;
            this.ciLow = ciLow;
            this.ciHigh = ciHigh;
            this.nrRunsPerLength = nrRunsPerLength;
            this.searchAttempts = searchAttempts;
            this.status = status;
            this.trials = trials;
        }

        static LevelResultSnapshot fromTrials(int level, String inputXmi, int optimalBlockCount, Integer confidentMin,
                Double expectedMin, int searchAttempts, int nrRuns, String status, List<LengthTrialSnapshot> trials) {
            LengthTrialSnapshot last = trials.isEmpty() ? null : trials.get(trials.size() - 1);
            double exp = expectedMin != null ? expectedMin : computeExpectedMinimum(trials);
            return new LevelResultSnapshot(level, inputXmi, optimalBlockCount, confidentMin, exp,
                    last != null ? last.successRate : 0,
                    last != null ? last.ciLow : 0,
                    last != null ? last.ciHigh : 0,
                    nrRuns, searchAttempts, status, trials);
        }

        static LevelResultSnapshot fromSummaryCsv(String line) {
            String[] p = parseCsvLine(line);
            return new LevelResultSnapshot(
                    Integer.parseInt(p[0].trim()),
                    p[1].trim(),
                    Integer.parseInt(p[2].trim()),
                    p[4].isBlank() ? null : Integer.parseInt(p[4].trim()),
                    p[3].isBlank() ? Double.NaN : Double.parseDouble(p[3].trim()),
                    p[5].isBlank() ? 0 : Double.parseDouble(p[5].trim()),
                    p[6].isBlank() ? 0 : Double.parseDouble(p[6].trim()),
                    p[7].isBlank() ? 0 : Double.parseDouble(p[7].trim()),
                    Integer.parseInt(p[8].trim()),
                    Integer.parseInt(p[9].trim()),
                    p[10].trim(),
                    List.of());
        }

        String toSummaryCsvRow() {
            String expected = Double.isNaN(expectedMin) ? "" : String.format("%.3f", expectedMin);
            String confident = confidentMin != null ? String.valueOf(confidentMin) : "";
            return level + "," + escapeCsvField(inputXmi) + "," + optimalBlockCount + ","
                    + expected + "," + confident + ","
                    + String.format("%.4f", successRate) + ","
                    + String.format("%.4f", ciLow) + ","
                    + String.format("%.4f", ciHigh) + ","
                    + nrRunsPerLength + "," + searchAttempts + "," + status;
        }

        private static String[] parseCsvLine(String line) {
            List<String> fields = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean inQuotes = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    inQuotes = !inQuotes;
                } else if (c == ',' && !inQuotes) {
                    fields.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            fields.add(cur.toString());
            return fields.toArray(new String[0]);
        }

        private static String escapeCsvField(String field) {
            if (field == null) return "";
            if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
                return "\"" + field.replace("\"", "\"\"") + "\"";
            }
            return field;
        }
    }
}
