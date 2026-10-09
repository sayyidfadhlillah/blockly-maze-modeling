package blocky_game;

import blocky.Level;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Encapsulates per-guest session state, including an independent GameEngine,
 * session workspace directory for MOMoT runs, and status tracking.
 */
public class SessionContext {

    private final String sessionId;
    private final GameEngine engine;
    private final File sessionDir;
    private volatile long lastAccessedTime;

    private volatile boolean isMomotRunning;
    private volatile String momotStatus = "Idle";
    private volatile Thread momotThread;
    private final List<String> momotLogBuffer = new CopyOnWriteArrayList<>();
    private volatile String momotCurrentOutputDir;
    /** Level id that {@link #momotCurrentOutputDir} belongs to. -1 means no active results. */
    private volatile int momotCurrentLevelId = -1;

    private volatile int progressRun = 0;
    private volatile int progressTotalRuns = 0;
    private volatile int progressGen = 0;
    private volatile int progressTotalGens = 0;
    private volatile double progressPct = 0.0;
    /**
     * Last applied page epoch. Monotonically increasing per page load in the web client.
     * Requests with an epoch older than this value are rejected.
     */
    private long lastAppliedEpoch = 0;
    /**
     * Bumped when a search starts or the level changes so a finishing search from a
     * previous level cannot overwrite this session's output directory or status.
     */
    private final AtomicLong searchGeneration = new AtomicLong();

    public SessionContext(String sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId);
        this.engine = new GameEngine();
        this.engine.initializeGame();
        this.lastAccessedTime = System.currentTimeMillis();

        File baseDir = new File("momot_sessions");
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
        this.sessionDir = new File(baseDir, "session_" + sessionId);
        if (!this.sessionDir.exists()) {
            this.sessionDir.mkdirs();
        }
    }

    public void touch() {
        this.lastAccessedTime = System.currentTimeMillis();
    }

    public long getLastAccessedTime() {
        return lastAccessedTime;
    }

    public String getSessionId() {
        return sessionId;
    }

    public GameEngine getEngine() {
        touch();
        return engine;
    }

    public File getSessionDir() {
        return sessionDir;
    }

    public synchronized long getLastAppliedEpoch() {
        return lastAppliedEpoch;
    }

    private boolean checkAndUpdateEpoch(long epoch) {
        if (epoch > 0) {
            if (epoch < lastAppliedEpoch) {
                return false;
            }
            lastAppliedEpoch = epoch;
        }
        return true;
    }

    public synchronized boolean syncModel(String xml) {
        return syncModel(0, xml, false);
    }

    public synchronized boolean syncModel(long epoch, String xml, boolean allowEmpty) {
        touch();
        if (!checkAndUpdateEpoch(epoch)) {
            return false;
        }
        applyModelXml(xml, allowEmpty);
        return true;
    }

    private void applyModelXml(String xml, boolean allowEmpty) {
        if (xml == null) return;
        boolean hasBlocks = xml.indexOf("<block") >= 0;
        if (!hasBlocks) {
            if (allowEmpty) {
                try {
                    engine.rebuildProgram(Collections.emptyList());
                } catch (Exception e) {
                    System.err.println("[SessionContext " + sessionId + "] syncModel clear error: " + e.getMessage());
                }
            }
            return;
        }
        try {
            List<Map<String, Object>> data = BlocklyXmlParser.parseBlocklyXml(xml);
            engine.rebuildProgram(data);
        } catch (Exception e) {
            System.err.println("[SessionContext " + sessionId + "] syncModel error: " + e.getMessage());
        }
    }

    public synchronized boolean syncMap(String mapJson) {
        return syncMap(0, mapJson);
    }

    public synchronized boolean syncMap(long epoch, String mapJson) {
        touch();
        if (!checkAndUpdateEpoch(epoch)) {
            return false;
        }
        if (mapJson == null || mapJson.trim().isEmpty()) return true;
        try {
            engine.setMapFromJson(mapJson);
            clearMomotState();
        } catch (Exception e) {
            System.err.println("[SessionContext " + sessionId + "] syncMap error: " + e.getMessage());
        }
        return true;
    }

    public synchronized boolean syncLevelMeta(String metaJson) {
        return syncLevelMeta(0, metaJson);
    }

    public synchronized boolean syncLevelMeta(long epoch, String metaJson) {
        touch();
        if (!checkAndUpdateEpoch(epoch)) {
            return false;
        }
        if (metaJson == null || metaJson.trim().isEmpty()) return true;
        try {
            int oldLevelId = (engine.getCurrentLevel() != null) ? engine.getCurrentLevel().getId() : -1;
            engine.syncLevelMeta(metaJson);
            int newLevelId = (engine.getCurrentLevel() != null) ? engine.getCurrentLevel().getId() : -1;
            if (oldLevelId != newLevelId) {
                clearMomotState();
            }
        } catch (Exception e) {
            System.err.println("[SessionContext " + sessionId + "] syncLevelMeta error: " + e.getMessage());
        }
        return true;
    }

    public synchronized boolean applySnapshot(long epoch, String mapJson, String metaJson, String xml, boolean allowEmpty) {
        touch();
        if (!checkAndUpdateEpoch(epoch)) {
            return false;
        }
        if (mapJson != null && !mapJson.trim().isEmpty()) {
            try {
                engine.setMapFromJson(mapJson);
                clearMomotState();
            } catch (Exception e) {
                System.err.println("[SessionContext " + sessionId + "] syncMap error: " + e.getMessage());
            }
        }
        if (metaJson != null && !metaJson.trim().isEmpty()) {
            try {
                int oldLevelId = (engine.getCurrentLevel() != null) ? engine.getCurrentLevel().getId() : -1;
                engine.syncLevelMeta(metaJson);
                int newLevelId = (engine.getCurrentLevel() != null) ? engine.getCurrentLevel().getId() : -1;
                if (oldLevelId != newLevelId) {
                    clearMomotState();
                }
            } catch (Exception e) {
                System.err.println("[SessionContext " + sessionId + "] syncLevelMeta error: " + e.getMessage());
            }
        }
        if (xml != null) {
            applyModelXml(xml, allowEmpty);
        }
        return true;
    }

    public synchronized void clearMomotState() {
        // Invalidate in-flight callbacks before interrupting so a late finish cannot
        // restore the previous level's output directory.
        searchGeneration.incrementAndGet();
        if (isMomotRunning) {
            stopMomotRun();
        }
        this.momotCurrentOutputDir = null;
        this.momotCurrentLevelId = -1;
        this.momotLogBuffer.clear();
        this.momotStatus = "Idle";
    }

    public synchronized void runMomotWithParams(int seed, int pop, int eval, int runs, int solLen) {
        runMomotWithParams(0, null, null, null, seed, pop, eval, runs, solLen);
    }

    public synchronized void runMomotWithParams(long epoch, String mapJson, String metaJson, String xml,
                                                int seed, int pop, int eval, int runs, int solLen) {
        touch();
        if (epoch > 0 && epoch < lastAppliedEpoch) {
            momotLogBuffer.add("[Session] Stale epoch ignored: " + epoch + " < " + lastAppliedEpoch);
            return;
        }
        if (mapJson != null || metaJson != null || xml != null || epoch > 0) {
            boolean applied = applySnapshot(epoch, mapJson, metaJson, xml, true);
            if (!applied) {
                return;
            }
        }
        if (isMomotRunning) {
            momotLogBuffer.add("[Session] MoMoT run already in progress.");
            return;
        }

        final long runGen = searchGeneration.incrementAndGet();
        final int levelId = currentLevelId();

        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date()) + "_" + runGen;
        File runDir = new File(sessionDir, "run_" + ts);
        if (!runDir.exists() && !runDir.mkdirs()) {
            momotLogBuffer.add("[Error] Failed to create MoMoT run directory: " + runDir.getAbsolutePath());
            return;
        }
        // Keep the input beside the output folder. MomotRunService deletes the output
        // directory at the start of an isolated run, so the input must not live inside it.
        File inputXmi = new File(runDir, "input.xmi");
        File outDir = new File(runDir, "output");
        try {
            engine.saveToFile(inputXmi);
        } catch (Exception e) {
            System.err.println("[SessionContext " + sessionId + "] saveToFile failed: " + e.getMessage());
            momotLogBuffer.add("[Error] Failed to save input model for MoMoT run: " + e.getMessage());
            return;
        }
        if (!inputXmi.exists()) {
            momotLogBuffer.add("[Error] Failed to save input model for MoMoT run.");
            return;
        }

        this.momotCurrentOutputDir = outDir.getAbsolutePath();
        this.momotCurrentLevelId = levelId;

        if (System.getProperty("blocky.objectives") == null) {
            System.setProperty("blocky.objectives", "GATED");
        }
        if (System.getProperty("blocky.nonGoalArchive") == null) {
            System.setProperty("blocky.nonGoalArchive", "10");
        }
        if (System.getProperty("blocky.henshin") == null) {
            String defaultModule = MomotFirstGoalBenchmarkRunner.selectHenshinModuleForLevel(levelId);
            String henshinPath = MomotRunService.firstExisting(
                    "blocky_model/transformations/" + defaultModule,
                    "../blocky_model/transformations/" + defaultModule,
                    defaultModule
            );
            File resolved = MomotRunService.resolveExistingFile(henshinPath);
            if (resolved.exists()) {
                System.setProperty("blocky.henshin", resolved.getAbsolutePath());
            }
        }

        MomotRunService.RunSpec spec = new MomotRunService.RunSpec(
            inputXmi.getAbsolutePath(),
            outDir.getAbsolutePath(),
            pop, eval, runs, solLen, false, seed, sessionId
        );

        isMomotRunning = true;
        momotStatus = "Waiting";
        momotLogBuffer.clear();
        momotLogBuffer.add("[MoMoT] Starting search for session " + sessionId + "...");

        final int nrRuns = Math.max(1, runs);
        final int evalsPerRun = Math.max(1, eval);
        final int generationsPerRun = Math.max(1, eval / Math.max(1, pop));
        this.progressTotalRuns = nrRuns;
        this.progressTotalGens = generationsPerRun;
        this.progressRun = 1;
        this.progressGen = 0;
        this.progressPct = 0.0;

        final java.util.concurrent.atomic.AtomicInteger curRun = new java.util.concurrent.atomic.AtomicInteger(1);
        final java.util.concurrent.atomic.AtomicInteger lastNfe = new java.util.concurrent.atomic.AtomicInteger(-1);

        java.util.function.BiConsumer<Integer, Object> subscriber = (nfe, paretoFront) -> {
            if (runGen != searchGeneration.get()) return;
            int n = nfe == null ? 0 : Math.max(0, nfe);
            int prev = lastNfe.getAndSet(n);
            if (prev >= 0 && n < prev && curRun.get() < nrRuns) {
                curRun.incrementAndGet();
            }
            int r = curRun.get();
            int g = Math.min(generationsPerRun, (n + Math.max(1, pop) - 1) / Math.max(1, pop));
            double pct = Math.min(100.0, 100.0 * ((r - 1) * (double) evalsPerRun + Math.min(n, evalsPerRun))
                    / ((double) nrRuns * evalsPerRun));
            this.progressRun = r;
            this.progressGen = g;
            this.progressPct = pct;
        };

        this.momotThread = MomotRunService.runAsync(spec, log -> {
            if (runGen != searchGeneration.get()) return;
            momotLogBuffer.add(log);
        }, status -> {
            if (runGen != searchGeneration.get()) return;
            this.momotStatus = status;
        }, () -> {
            if (runGen != searchGeneration.get()) return;
            isMomotRunning = false;
            this.progressPct = 100.0;
            this.progressRun = this.progressTotalRuns;
            this.progressGen = this.progressTotalGens;
            if (!"Stopped".equals(momotStatus)) {
                momotStatus = "Finished";
                momotLogBuffer.add("[MoMoT] Search completed.");
            }
            momotThread = null;
        }, dir -> {
            if (runGen != searchGeneration.get()) return;
            if (currentLevelId() != levelId) return;
            momotCurrentOutputDir = dir;
        }, subscriber);
    }

    public synchronized void stopMomotRun() {
        touch();
        if (isMomotRunning) {
            MomotRunService.stopMomotSearch(this.sessionId);
            Thread t = this.momotThread;
            if (t != null && t.isAlive()) {
                MomotRunService.stopRun(t);
            }
            this.momotThread = null;
            isMomotRunning = false;
            momotStatus = "Stopped";
            momotLogBuffer.add("[MoMoT] Search stopped by user.");
            this.progressPct = 100.0;
        }
    }

    public int getProgressRun() { return progressRun; }
    public int getProgressTotalRuns() { return progressTotalRuns; }
    public int getProgressGen() { return progressGen; }
    public int getProgressTotalGens() { return progressTotalGens; }
    public double getProgressPct() { return progressPct; }

    public synchronized List<MomotResultsService.SolutionEntry> listMomotSolutions() {
        touch();
        if (momotCurrentOutputDir == null || momotCurrentLevelId != currentLevelId()) {
            return Collections.emptyList();
        }
        File outDir = new File(momotCurrentOutputDir);
        if (outDir.exists() && outDir.isDirectory()) {
            return MomotResultsService.loadFromOutputDir(outDir);
        }
        return Collections.emptyList();
    }

    private int currentLevelId() {
        Level level = engine.getCurrentLevel();
        return level != null ? level.getId() : -1;
    }

    public synchronized boolean loadMomotSolution(String modelPath) {
        touch();
        if (modelPath == null || modelPath.trim().isEmpty()) return false;
        try {
            File f = new File(modelPath.trim());
            if (!f.exists()) return false;
            engine.loadFromFile(f);
            return true;
        } catch (Exception e) {
            System.err.println("[SessionContext " + sessionId + "] loadMomotSolution failed: " + e.getMessage());
            return false;
        }
    }

    public boolean isMomotRunning() {
        return isMomotRunning;
    }

    public String getMomotStatus() {
        return momotStatus;
    }

    public List<String> getMomotLogs() {
        return new ArrayList<>(momotLogBuffer);
    }

    public String getMomotCurrentOutputDir() {
        return momotCurrentOutputDir;
    }
}
