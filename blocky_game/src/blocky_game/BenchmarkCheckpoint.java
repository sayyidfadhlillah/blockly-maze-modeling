package blocky_game;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persists benchmark progress so runs can be resumed after interruption.
 */
public final class BenchmarkCheckpoint {

    private static final Pattern TRIAL_LINE = Pattern.compile(
            "\\[MinSolutionLength\\] Level (\\d+) length=(\\d+) successes=(\\d+)/(\\d+) rate=([0-9.]+) wilsonCI=\\[([0-9.]+), ([0-9.]+)\\]");
    private static final Pattern FINISHED_LINE = Pattern.compile(
            "\\[MinSolutionLength\\] Level (\\d+) FINISHED: status=(\\w+) confidentMin=(\\d*) expectedMin=([0-9.]*) searchAttempts=(\\d+)");
    private static final Pattern TRYING_LINE = Pattern.compile(
            "\\[MinSolutionLength\\] Level (\\d+) trying solutionLength=(\\d+)");

    private final File checkpointDir;
    private final String sessionId;

    public BenchmarkCheckpoint(String sessionId) {
        this.sessionId = sessionId;
        this.checkpointDir = new File(resolveAnalysisDir(), "checkpoints/" + sessionId);
        this.checkpointDir.mkdirs();
    }

    public String getSessionId() {
        return sessionId;
    }

    public File getCheckpointDir() {
        return checkpointDir;
    }

    public static String findLatestSessionId() {
        File root = new File(resolveAnalysisDir(), "checkpoints");
        if (!root.isDirectory()) {
            return null;
        }
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) {
            return null;
        }
        File latest = dirs[0];
        for (File d : dirs) {
            if (d.getName().compareTo(latest.getName()) > 0) {
                latest = d;
            }
        }
        return latest.getName();
    }

    public void saveSession(Properties props) throws IOException {
        File f = new File(checkpointDir, "session.properties");
        try (FileWriter fw = new FileWriter(f, StandardCharsets.UTF_8)) {
            props.store(fw, "Benchmark session");
        }
    }

    public Properties loadSession() throws IOException {
        File f = new File(checkpointDir, "session.properties");
        Properties props = new Properties();
        if (!f.isFile()) {
            return props;
        }
        try (FileReader fr = new FileReader(f, StandardCharsets.UTF_8)) {
            props.load(fr);
        }
        return props;
    }

    public boolean isLevelDone(int level) {
        return new File(checkpointDir, levelFile(level, "done.csv")).isFile();
    }

    public void saveLevelDone(MomotMinSolutionLengthRunner.LevelResultSnapshot result) throws IOException {
        File f = new File(checkpointDir, levelFile(result.level, "done.csv"));
        try (FileWriter fw = new FileWriter(f, StandardCharsets.UTF_8)) {
            fw.write("level,inputXmi,optimalBlockCount,expectedMinSolutionLength,confidentMinSolutionLength,"
                    + "successRate,ciLow,ciHigh,nrRunsPerLength,lengthsTried,status\n");
            fw.write(result.toSummaryCsvRow());
        }
        new File(checkpointDir, levelFile(result.level, "progress.properties")).delete();
    }

    public MomotMinSolutionLengthRunner.LevelResultSnapshot loadLevelDone(int level) throws IOException {
        File f = new File(checkpointDir, levelFile(level, "done.csv"));
        if (!f.isFile()) {
            return null;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
            br.readLine();
            String line = br.readLine();
            if (line == null) {
                return null;
            }
            return MomotMinSolutionLengthRunner.LevelResultSnapshot.fromSummaryCsv(line);
        }
    }

    public List<MomotMinSolutionLengthRunner.LengthTrialSnapshot> loadTrials(int level) throws IOException {
        File f = new File(checkpointDir, levelFile(level, "trials.csv"));
        List<MomotMinSolutionLengthRunner.LengthTrialSnapshot> trials = new ArrayList<>();
        if (!f.isFile()) {
            return trials;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
            String header = br.readLine();
            if (header == null) {
                return trials;
            }
            String line;
            while ((line = br.readLine()) != null) {
                trials.add(MomotMinSolutionLengthRunner.LengthTrialSnapshot.fromTrialsCsv(line));
            }
        }
        return trials;
    }

    public void appendTrial(int level, MomotMinSolutionLengthRunner.LengthTrialSnapshot trial) throws IOException {
        File f = new File(checkpointDir, levelFile(level, "trials.csv"));
        boolean writeHeader = !f.exists();
        try (FileWriter fw = new FileWriter(f, StandardCharsets.UTF_8, true)) {
            if (writeHeader) {
                fw.write("solutionLength,successes,trials,successRate,ciLow,ciHigh\n");
            }
            fw.write(trial.toTrialsCsvRow());
        }
    }

    public void saveProgress(int level, int nextSolutionLength) throws IOException {
        Properties props = new Properties();
        props.setProperty("nextSolutionLength", String.valueOf(nextSolutionLength));
        File f = new File(checkpointDir, levelFile(level, "progress.properties"));
        try (FileWriter fw = new FileWriter(f, StandardCharsets.UTF_8)) {
            props.store(fw, "In-progress level");
        }
    }

    public int loadNextSolutionLength(int level, int defaultStart) throws IOException {
        File f = new File(checkpointDir, levelFile(level, "progress.properties"));
        if (!f.isFile()) {
            List<MomotMinSolutionLengthRunner.LengthTrialSnapshot> trials = loadTrials(level);
            if (trials.isEmpty()) {
                return defaultStart;
            }
            int lastLen = trials.get(trials.size() - 1).solutionLength;
            return lastLen + 1;
        }
        Properties props = new Properties();
        try (FileReader fr = new FileReader(f, StandardCharsets.UTF_8)) {
            props.load(fr);
        }
        return Integer.parseInt(props.getProperty("nextSolutionLength", String.valueOf(defaultStart)));
    }

    public void writeMergedOutputs(double minSuccessRate, double confidenceLevel) throws IOException {
        File analysisDir = resolveAnalysisDir();
        File summaryCsv = new File(analysisDir, "min_solution_length_" + sessionId + ".csv");
        File trialsCsv = new File(analysisDir, "solution_length_trials_" + sessionId + ".csv");

        List<Integer> levels = listLevelsWithData();
        try (FileWriter summary = new FileWriter(summaryCsv, StandardCharsets.UTF_8);
             FileWriter trials = new FileWriter(trialsCsv, StandardCharsets.UTF_8)) {
            summary.write("level,inputXmi,optimalBlockCount,expectedMinSolutionLength,confidentMinSolutionLength,"
                    + "successRate,ciLow,ciHigh,nrRunsPerLength,lengthsTried,status,minSuccessRate,confidenceLevel\n");
            trials.write("level,solutionLength,successes,trials,successRate,ciLow,ciHigh\n");

            for (int level : levels) {
                MomotMinSolutionLengthRunner.LevelResultSnapshot done = loadLevelDone(level);
                if (done != null) {
                    summary.write(done.toSummaryCsvRow() + "," + minSuccessRate + "," + confidenceLevel + "\n");
                }
                for (MomotMinSolutionLengthRunner.LengthTrialSnapshot t : loadTrials(level)) {
                    trials.write(level + "," + t.toTrialsCsvRow());
                }
            }
        }
        System.out.println("[Checkpoint] Wrote partial summary: " + summaryCsv.getAbsolutePath());
        System.out.println("[Checkpoint] Wrote partial trials: " + trialsCsv.getAbsolutePath());
    }

    public static void importFromRunLog(File logFile, String sessionId, double minSuccessRate, double confidenceLevel)
            throws IOException {
        BenchmarkCheckpoint cp = new BenchmarkCheckpoint(sessionId);

        Properties session = new Properties();
        session.setProperty("sessionId", sessionId);
        session.setProperty("minSuccessRate", String.valueOf(minSuccessRate));
        session.setProperty("confidenceLevel", String.valueOf(confidenceLevel));
        session.setProperty("nrRuns", "30");
        cp.saveSession(session);

        int[][] trialData = new int[11][];
        double[][] trialStats = new double[11][];
        int[] trialCounts = new int[11];
        boolean[] done = new boolean[11];
        Integer[] confidentMin = new Integer[11];
        Double[] expectedMin = new Double[11];
        String[] status = new String[11];
        int[] searchAttempts = new int[11];
        int[] pendingTryLength = new int[11];

        try (BufferedReader br = new BufferedReader(new FileReader(logFile, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                Matcher tm = TRIAL_LINE.matcher(line);
                if (tm.find()) {
                    int level = Integer.parseInt(tm.group(1));
                    int len = Integer.parseInt(tm.group(2));
                    int succ = Integer.parseInt(tm.group(3));
                    int n = Integer.parseInt(tm.group(4));
                    double rate = Double.parseDouble(tm.group(5));
                    double ciLow = Double.parseDouble(tm.group(6));
                    double ciHigh = Double.parseDouble(tm.group(7));
                    appendTrialRecord(trialData, trialStats, trialCounts, level, len, succ, n, rate, ciLow, ciHigh);
                    pendingTryLength[level] = 0;
                    continue;
                }
                Matcher fm = FINISHED_LINE.matcher(line);
                if (fm.find()) {
                    int level = Integer.parseInt(fm.group(1));
                    done[level] = true;
                    status[level] = fm.group(2);
                    if (!fm.group(3).isBlank()) {
                        confidentMin[level] = Integer.parseInt(fm.group(3));
                    }
                    if (!fm.group(4).isBlank()) {
                        expectedMin[level] = Double.parseDouble(fm.group(4));
                    }
                    searchAttempts[level] = Integer.parseInt(fm.group(5));
                    pendingTryLength[level] = 0;
                    continue;
                }
                Matcher ym = TRYING_LINE.matcher(line);
                if (ym.find()) {
                    int level = Integer.parseInt(ym.group(1));
                    pendingTryLength[level] = Integer.parseInt(ym.group(2));
                }
            }
        }

        for (int level = 1; level <= 10; level++) {
            for (int i = 0; i < trialCounts[level]; i++) {
                int idx = i * 6;
                MomotMinSolutionLengthRunner.LengthTrialSnapshot snap =
                        new MomotMinSolutionLengthRunner.LengthTrialSnapshot(
                                trialData[level][idx],
                                trialData[level][idx + 1],
                                trialData[level][idx + 2],
                                trialStats[level][idx + 3],
                                trialStats[level][idx + 4],
                                trialStats[level][idx + 5]);
                cp.appendTrial(level, snap);
            }

            if (done[level]) {
                List<MomotMinSolutionLengthRunner.LengthTrialSnapshot> trials = cp.loadTrials(level);
                MomotMinSolutionLengthRunner.LevelResultSnapshot result =
                        MomotMinSolutionLengthRunner.LevelResultSnapshot.fromTrials(
                                level, "", optimalFor(level), confidentMin[level], expectedMin[level],
                                searchAttempts[level], 30, status[level], trials);
                cp.saveLevelDone(result);
            } else if (trialCounts[level] > 0 || pendingTryLength[level] > 0) {
                int next = pendingTryLength[level] > 0
                        ? pendingTryLength[level]
                        : trialData[level][(trialCounts[level] - 1) * 6] + 1;
                cp.saveProgress(level, next);
            }
        }

        cp.writeMergedOutputs(minSuccessRate, confidenceLevel);
        System.out.println("[Checkpoint] Imported session " + sessionId + " from " + logFile.getAbsolutePath());
    }

    private static void appendTrialRecord(int[][] data, double[][] stats, int[] counts, int level,
            int len, int succ, int n, double rate, double ciLow, double ciHigh) {
        int c = counts[level];
        if (data[level] == null) {
            data[level] = new int[600];
            stats[level] = new double[600];
        }
        int base = c * 6;
        data[level][base] = len;
        data[level][base + 1] = succ;
        data[level][base + 2] = n;
        stats[level][base + 3] = rate;
        stats[level][base + 4] = ciLow;
        stats[level][base + 5] = ciHigh;
        counts[level] = c + 1;
    }

    private static int optimalFor(int level) {
        int[] opts = {2, 5, 2, 5, 5, 4, 4, 5, 4, 7};
        if (level >= 1 && level <= opts.length) {
            return opts[level - 1];
        }
        return 5;
    }

    private List<Integer> listLevelsWithData() throws IOException {
        List<Integer> levels = new ArrayList<>();
        for (int level = 1; level <= 10; level++) {
            if (isLevelDone(level) || new File(checkpointDir, levelFile(level, "trials.csv")).isFile()) {
                levels.add(level);
            }
        }
        levels.sort(Comparator.naturalOrder());
        return levels;
    }

    private static String levelFile(int level, String suffix) {
        return String.format("level_%02d_%s", level, suffix);
    }

    private static File resolveAnalysisDir() {
        File f1 = new File("blocky_momot/analysis");
        File f2 = new File("../blocky_momot/analysis");
        if (f1.exists() || f1.mkdirs()) return f1;
        if (f2.exists() || f2.mkdirs()) return f2;
        return f1;
    }
}
