package blocky_game;

import javafx.application.Platform;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Robust MoMoT runner for Docker/Maven environments.
 */
public final class MomotRunService {

    private MomotRunService() {}

    public static class RunSpec {
        public final String inputXmi;
        public final String outputBase;
        public final int populationSize;
        public final int maxEvaluations;
        public final int nrRuns;
        public final int solutionLength;
        public final boolean stopOnFirstGoal;
        public final int seed;
        public final String sessionId;

        public RunSpec(String inputXmi, String outputBase) {
            this(inputXmi, outputBase, -1, -1, -1, -1, false, -1, null);
        }

        public RunSpec(String inputXmi, String outputBase, int populationSize, int maxEvaluations, int nrRuns, int solutionLength) {
            this(inputXmi, outputBase, populationSize, maxEvaluations, nrRuns, solutionLength, false, -1, null);
        }

        public RunSpec(String inputXmi, String outputBase, int populationSize, int maxEvaluations, int nrRuns, int solutionLength, int seed) {
            this(inputXmi, outputBase, populationSize, maxEvaluations, nrRuns, solutionLength, false, seed, null);
        }

        public RunSpec(String inputXmi, String outputBase, int populationSize, int maxEvaluations, int nrRuns, int solutionLength, int seed, String sessionId) {
            this(inputXmi, outputBase, populationSize, maxEvaluations, nrRuns, solutionLength, false, seed, sessionId);
        }

        public RunSpec(String inputXmi, String outputBase, int populationSize, int maxEvaluations, int nrRuns, int solutionLength, boolean stopOnFirstGoal) {
            this(inputXmi, outputBase, populationSize, maxEvaluations, nrRuns, solutionLength, stopOnFirstGoal, -1, null);
        }

        public RunSpec(String inputXmi, String outputBase, int populationSize, int maxEvaluations, int nrRuns, int solutionLength, boolean stopOnFirstGoal, int seed) {
            this(inputXmi, outputBase, populationSize, maxEvaluations, nrRuns, solutionLength, stopOnFirstGoal, seed, null);
        }

        public RunSpec(String inputXmi, String outputBase, int populationSize, int maxEvaluations, int nrRuns, int solutionLength, boolean stopOnFirstGoal, int seed, String sessionId) {
            this.inputXmi = Objects.requireNonNull(inputXmi);
            this.outputBase = Objects.requireNonNull(outputBase);
            this.populationSize = populationSize;
            this.maxEvaluations = maxEvaluations;
            this.nrRuns = nrRuns;
            this.solutionLength = solutionLength;
            this.stopOnFirstGoal = stopOnFirstGoal;
            this.seed = seed;
            this.sessionId = sessionId;
        }
    }

    public static RunSpec defaultDirectManipulationSpec() {
        String input = firstExisting(
                "blocky_momot/model/input/direct_manipulation_request.xmi",
                "../blocky_momot/model/input/direct_manipulation_request.xmi",
                "direct_manipulation_request_momot.xmi",
                "blocky_game/direct_manipulation_request.xmi",
                "../blocky_game/direct_manipulation_request.xmi",
                "direct_manipulation_request.xmi"
        );

        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String out = firstWritableParent(
                "blocky_momot/output_dm_" + ts,
                "../blocky_momot/output_dm_" + ts,
                "output_dm_" + ts
        );

        return new RunSpec(input, out);
    }

    private static final java.util.Set<Thread> ACTIVE_SEARCH_THREADS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.ConcurrentHashMap<String, Thread> ACTIVE_SESSION_THREADS = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Thread currentMomotThread;
    private static final Object RUNNER_CLASS_LOCK = new Object();
    /** Henshin/EMF matching serialization lock reserved for synchronous benchmark runners. */
    private static final Object MOMOT_EXECUTION_LOCK = new Object();
    private static final Semaphore SEARCH_SEMAPHORE = new Semaphore(10, true);
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private static final Object STREAM_INSTALL_LOCK = new Object();
    private static volatile boolean streamsInstalled = false;

    private static volatile Class<?> cachedRunnerClass;
    private static volatile ClassLoader cachedRunnerClassLoader;

    /** Ensure process-wide stdout/stderr mirrors and thread-local PRNG are installed once. */
    public static void ensureSystemStreamsInstalled() {
        blocky_momot.ThreadLocalRandomProxy.install();
        if (!streamsInstalled) {
            synchronized (STREAM_INSTALL_LOCK) {
                if (!streamsInstalled) {
                    PrintStream origOut = System.out;
                    PrintStream origErr = System.err;
                    System.setOut(new PrintStream(new ThreadMirroringOutputStream(origOut, false), true, StandardCharsets.UTF_8));
                    System.setErr(new PrintStream(new ThreadMirroringOutputStream(origErr, true), true, StandardCharsets.UTF_8));
                    streamsInstalled = true;
                }
            }
        }
    }

    /** Pre-load the MoMoT runner class (required before parallel benchmark runs). */
    public static void warmupRunnerClass() {
        try {
            ensureSystemStreamsInstalled();
            ensureBlockyInputForClassInit();
            resolveRunnerClass(MomotRunService.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to load MoMoT runner class", e);
        }
    }

    public static String runSync(RunSpec spec, Consumer<String> logLine, Consumer<String> onOutputDirReady) {
        return runSync(spec, logLine, onOutputDirReady, null);
    }

    public static String runSync(RunSpec spec, Consumer<String> logLine, Consumer<String> onOutputDirReady, Object subscriber) {
        synchronized (MOMOT_EXECUTION_LOCK) {
            return runInternal(spec, logLine, onOutputDirReady, subscriber);
        }
    }

    public static void stopCurrentRun() {
        stopMomotSearch("desktop");
        Thread t = currentMomotThread;
        if (t != null && t.isAlive()) {
            stopRun(t);
        }
    }

    public static void stopMomotSearch(String sessionId) {
        if (sessionId == null) return;
        Thread t = ACTIVE_SESSION_THREADS.get(sessionId);
        if (t != null && t.isAlive()) {
            stopRun(t);
        }
    }

    public static void stopAllSearches() {
        for (Thread t : ACTIVE_SEARCH_THREADS) {
            if (t != null && t.isAlive()) {
                try {
                    t.interrupt();
                } catch (Exception ignored) {}
            }
        }
    }

    public static void stopRun(Thread thread) {
        if (thread != null && thread.isAlive()) {
            try {
                thread.interrupt();
            } catch (Exception ignored) {}
        }
    }

    public static Thread runAsync(RunSpec spec, Consumer<String> logLine, Runnable onDone, Consumer<String> onOutputDirReady) {
        return runAsync(spec, logLine, null, onDone, onOutputDirReady, null);
    }

    public static Thread runAsync(RunSpec spec, Consumer<String> logLine, Runnable onDone, Consumer<String> onOutputDirReady, Object subscriber) {
        return runAsync(spec, logLine, null, onDone, onOutputDirReady, subscriber);
    }

    public static Thread runAsync(RunSpec spec, Consumer<String> logLine, Consumer<String> onStatusChange, Runnable onDone, Consumer<String> onOutputDirReady) {
        return runAsync(spec, logLine, onStatusChange, onDone, onOutputDirReady, null);
    }

    public static Thread runAsync(RunSpec spec, Consumer<String> logLine, Consumer<String> onStatusChange, Runnable onDone, Consumer<String> onOutputDirReady, Object subscriber) {
        ensureSystemStreamsInstalled();
        final String sid = spec.sessionId;
        Thread t = new Thread(() -> {
            boolean acquired = false;
            try {
                ACTIVE_SEARCH_THREADS.add(Thread.currentThread());
                if (sid != null) {
                    ACTIVE_SESSION_THREADS.put(sid, Thread.currentThread());
                }
                if ("desktop".equals(sid)) {
                    currentMomotThread = Thread.currentThread();
                }
                if (onStatusChange != null) {
                    onStatusChange.accept("Waiting");
                }
                SEARCH_SEMAPHORE.acquire();
                acquired = true;
                if (onStatusChange != null) {
                    onStatusChange.accept("Running");
                }
                runInternal(spec, logLine, onOutputDirReady, subscriber);
            } catch (InterruptedException e) {
                if (logLine != null) {
                    logLine.accept("[MoMoT] Search interrupted/cancelled.");
                }
            } catch (Throwable t2) {
                if (logLine != null) {
                    logLine.accept("[MoMoT] Failed:\n" + throwableToString(t2));
                }
            } finally {
                ACTIVE_SEARCH_THREADS.remove(Thread.currentThread());
                if (sid != null) {
                    ACTIVE_SESSION_THREADS.remove(sid, Thread.currentThread());
                }
                if ("desktop".equals(sid) && currentMomotThread == Thread.currentThread()) {
                    currentMomotThread = null;
                }
                if (acquired) {
                    SEARCH_SEMAPHORE.release();
                }
                if (onDone != null) {
                    try {
                        Platform.runLater(onDone);
                    } catch (Throwable ignored) {
                        onDone.run();
                    }
                }
            }
        }, "MomotRunService-" + THREAD_COUNTER.incrementAndGet());
        ACTIVE_SEARCH_THREADS.add(t);
        if (sid != null) {
            ACTIVE_SESSION_THREADS.put(sid, t);
        }
        if (sid == null || "desktop".equals(sid)) {
            currentMomotThread = t;
        }
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void findJars(File dir, List<URL> urls) {
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) findJars(f, urls);
            else if (f.getName().endsWith(".jar")) {
                try { urls.add(f.toURI().toURL()); } catch (Exception ignored) {}
            }
        }
    }

    private static String runInternal(RunSpec spec, Consumer<String> logLine, Consumer<String> onOutputDirReady, Object subscriber) {
        ensureSystemStreamsInstalled();
        ThreadMirroringOutputStream.setThreadListener(logLine, s -> { if (logLine != null) logLine.accept("[stderr] " + s); });
        blocky_momot.ThreadLocalRandomProxy.install();
        if (spec.seed > 0) {
            blocky_momot.ThreadLocalRandomProxy.setThreadSeed(spec.seed);
        }

        if (logLine != null) logLine.accept("[MoMoT] Starting search logic...");

        File currentDir = new File(System.getProperty("user.dir"));
        Path outputDir = resolveOutputPath(currentDir, spec.outputBase);
        boolean isolatedOutput = spec.populationSize > 0 || spec.maxEvaluations > 0 || spec.nrRuns > 0 || spec.solutionLength > 0;

        if (isolatedOutput) {
            deleteDirectoryRecursive(outputDir);
            try {
                Files.createDirectories(outputDir);
            } catch (Exception ignored) {}
        } else {
            deleteDirectoryRecursive(Path.of("output"));
            deleteDirectoryRecursive(Path.of("blocky_momot", "output"));
        }

        ensureBlockyInputForClassInit();

        Class<?> runnerClass = null;
        ClassLoader finalCl = MomotRunService.class.getClassLoader();
        
        try {
            runnerClass = resolveRunnerClass(finalCl);
        } catch (Throwable e) {
            try {
                List<URL> urls = new ArrayList<>();
                File[] targets = { 
                    new File("/app/blocky_momot/target/classes"), 
                    new File("/app/blocky_game/target/classes"),
                    new File("blocky_momot/target/classes"), 
                    new File("blocky_game/target/classes") 
                };
                for (File f : targets) if (f.exists()) urls.add(f.toURI().toURL());
                findJars(new File("/app/blocky_game/target/all_deps"), urls);
                findJars(new File("/app/libs"), urls);
                findJars(new File("blocky_game/target/all_deps"), urls);
                findJars(new File("libs"), urls);
                URLClassLoader urlCl = new URLClassLoader(urls.toArray(new URL[0]), finalCl);
                runnerClass = resolveRunnerClass(urlCl);
                finalCl = urlCl;
            } catch (Throwable t) {
                if (logLine != null) logLine.accept("[MoMoT] Loader Error: " + t.toString());
                return null;
            }
        }

        if (runnerClass == null) return null;

        registerPackages(finalCl, logLine);

        ClassLoader originalTCCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(finalCl);
        
        try {
            String absInput = resolveExistingFile(spec.inputXmi).getAbsolutePath();

            int solLen = 10;
            if (spec.solutionLength > 0) {
                solLen = spec.solutionLength;
            } else {
                try {
                    Class<?> m = Class.forName("blocky_momot.BlockyProgramMetrics", true, finalCl);
                    Object v = m.getMethod("inferSolutionLength", String.class).invoke(null, absInput);
                    if (v instanceof Number) solLen = Math.max(1, ((Number) v).intValue() * 2);
                } catch (Throwable ignored) {}

                try {
                    String forced = System.getProperty("blocky.solutionLength");
                    if (forced != null && !forced.isBlank()) {
                        int v = Integer.parseInt(forced.trim());
                        if (v > 0) solLen = v;
                    } else {
                        String factorStr = System.getProperty("blocky.solutionLengthFactor");
                        if (factorStr != null && !factorStr.isBlank()) {
                            int factor = Integer.parseInt(factorStr.trim());
                            if (factor > 0) {
                                int baseline = Math.max(1, solLen / 2);
                                solLen = Math.max(1, baseline * factor);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            installThreadBaseline(absInput, finalCl);
            installRunContext(spec, outputDir, solLen, finalCl, subscriber);

            try {
                runnerClass.getMethod("initialization", String.class).invoke(null, absInput);
            } catch (Throwable e) {
                try { runnerClass.getMethod("initialization").invoke(null); } catch (Throwable ignored) {}
            }

            Object inst = runnerClass.getDeclaredConstructor().newInstance();
            
            if (!Thread.interrupted()) {
                // Hardened method discovery
                java.lang.reflect.Method m = null;
                Class<?> curr = inst.getClass();
                while (curr != null && m == null) {
                    for (java.lang.reflect.Method candidate : curr.getDeclaredMethods()) {
                        if (candidate.getName().equals("performSearch") && candidate.getParameterCount() == 2) {
                            m = candidate;
                            break;
                        }
                    }
                    curr = curr.getSuperclass();
                }
                if (m != null) {
                    m.setAccessible(true);
                    m.invoke(inst, absInput, solLen);
                }
            }
        } catch (Throwable t) {
            Throwable root = unwrapInvocationTargetException(t);
            if (logLine != null) {
                logLine.accept("[MoMoT] Exec Failed: " + root);
                logLine.accept("[MoMoT] Stacktrace:\n" + throwableToString(root));
            }
        } finally { 
            System.out.flush();
            System.err.flush();
            Thread.currentThread().setContextClassLoader(originalTCCL);
            clearThreadBaseline(finalCl);
            clearRunContext(finalCl);
            blocky_momot.ThreadLocalRandomProxy.clearThreadRandom();
            ThreadMirroringOutputStream.clearThreadListener();
        }

        return finalizeOutput(spec, onOutputDirReady, outputDir, isolatedOutput);
    }

    private static void installRunContext(RunSpec spec, Path outputDir, int solutionLength, ClassLoader cl, Object subscriber) {
        try {
            Class<?> ctxClass = Class.forName("blocky_momot_runner.MomotRunContext", true, cl);
            Class<?> cfgClass = Class.forName("blocky_momot_runner.MomotRunContext$Config", true, cl);
            Class<?> subClass = Class.forName("blocky_momot.listener.IParetoFrontSubscriber", true, cl);

            Object safeSubscriber = subscriber;
            if (subscriber != null && !subClass.isInstance(subscriber)) {
                safeSubscriber = java.lang.reflect.Proxy.newProxyInstance(
                        cl,
                        new Class<?>[]{subClass},
                        (proxy, method, args) -> {
                            String name = method.getName();
                            if ("equals".equals(name)) {
                                return proxy == (args != null && args.length > 0 ? args[0] : null);
                            }
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            }
                            if ("toString".equals(name)) {
                                return "IParetoFrontSubscriberProxy[" + subscriber + "]";
                            }
                            if ("onParetoFrontUpdated".equals(name) && args != null && args.length == 2) {
                                if (subscriber instanceof java.util.function.BiConsumer bi) {
                                    bi.accept(args[0], args[1]);
                                    return null;
                                }
                                if (subscriber instanceof Runnable r) {
                                    r.run();
                                    return null;
                                }
                                if (subscriber instanceof java.util.function.Consumer c) {
                                    c.accept(args[0]);
                                    return null;
                                }
                                try {
                                    java.lang.reflect.Method m = subscriber.getClass().getMethod("onParetoFrontUpdated", int.class, Object.class);
                                    return m.invoke(subscriber, args[0], args[1]);
                                } catch (Throwable t) {
                                    for (java.lang.reflect.Method candidate : subscriber.getClass().getMethods()) {
                                        if (candidate.getName().equals("onParetoFrontUpdated") && candidate.getParameterCount() == 2) {
                                            return candidate.invoke(subscriber, args[0], args[1]);
                                        }
                                    }
                                }
                            }
                            return null;
                        }
                );
            }

            Object cfg;
            try {
                cfg = cfgClass.getConstructor(int.class, int.class, int.class, int.class, Path.class, subClass, boolean.class, int.class)
                        .newInstance(spec.populationSize, spec.maxEvaluations, spec.nrRuns, solutionLength, outputDir, safeSubscriber, spec.stopOnFirstGoal, spec.seed);
            } catch (NoSuchMethodException e) {
                try {
                    cfg = cfgClass.getConstructor(int.class, int.class, int.class, int.class, Path.class, subClass, boolean.class)
                            .newInstance(spec.populationSize, spec.maxEvaluations, spec.nrRuns, solutionLength, outputDir, safeSubscriber, spec.stopOnFirstGoal);
                } catch (NoSuchMethodException e2) {
                    cfg = cfgClass.getConstructor(int.class, int.class, int.class, int.class, Path.class, subClass)
                            .newInstance(spec.populationSize, spec.maxEvaluations, spec.nrRuns, solutionLength, outputDir, safeSubscriber);
                }
            }
            ctxClass.getMethod("set", cfgClass).invoke(null, cfg);
        } catch (Throwable t) {
            System.err.println("[MomotRunService] Failed to install run context: " + t);
            t.printStackTrace();
        }
    }

    private static void clearRunContext(ClassLoader cl) {
        try {
            Class<?> ctxClass = Class.forName("blocky_momot_runner.MomotRunContext", true, cl);
            ctxClass.getMethod("clear").invoke(null);
        } catch (Throwable ignored) {}
    }

    private static Path resolveOutputPath(File currentDir, String outputBase) {
        Path target = Path.of(outputBase).normalize();
        if (!target.isAbsolute()) {
            target = new File(currentDir, outputBase).toPath();
        }
        return target;
    }

    private static void installThreadBaseline(String inputXmiPath, ClassLoader cl) {
        try {
            String absInput = resolveExistingFile(inputXmiPath).getAbsolutePath();
            try {
                Class<?> distClass = Class.forName("blocky_momot.BlockyProgramDistance", true, cl);
                distClass.getMethod("setThreadBaseline", String.class).invoke(null, absInput);
            } catch (Throwable t) {
                blocky_momot.BlockyProgramDistance.setThreadBaseline(absInput);
            }
        } catch (Throwable ignored) {}
    }

    private static void clearThreadBaseline(ClassLoader cl) {
        try {
            try {
                Class<?> distClass = Class.forName("blocky_momot.BlockyProgramDistance", true, cl);
                distClass.getMethod("clearThreadBaseline").invoke(null);
            } catch (Throwable t) {
                blocky_momot.BlockyProgramDistance.clearThreadBaseline();
            }
        } catch (Throwable ignored) {}
    }

    private static void ensureBlockyInputForClassInit() {
        try {
            String current = System.getProperty("blocky.input");
            if (current != null && !current.isBlank()) {
                File existing = resolveExistingFile(current);
                if (existing.exists() && existing.isFile()) {
                    System.setProperty("blocky.input", existing.getAbsolutePath());
                }
            } else {
                String fallback = firstExisting(
                        "blocky_momot/model/input/1.xmi",
                        "../blocky_momot/model/input/1.xmi",
                        "blocky_game/direct_manipulation_request.xmi",
                        "../blocky_game/direct_manipulation_request.xmi",
                        "direct_manipulation_request.xmi",
                        "model/input/1.xmi",
                        "model/1.xmi",
                        "model/input/game.xmi"
                );
                System.setProperty("blocky.input", resolveExistingFile(fallback).getAbsolutePath());
            }

            String currentHenshin = System.getProperty("blocky.henshin");
            if (currentHenshin != null && !currentHenshin.isBlank()) {
                if (Boolean.getBoolean("blocky.rules.wrap") && !currentHenshin.contains("_wrap")) {
                    String wrapped = MomotFirstGoalBenchmarkRunner.withWrapMoves(currentHenshin);
                    File wrappedFile = resolveExistingFile(wrapped);
                    if (wrappedFile.exists() && wrappedFile.isFile()) {
                        currentHenshin = wrappedFile.getAbsolutePath();
                    }
                }
                File existingHenshin = resolveExistingFile(currentHenshin);
                if (existingHenshin.exists() && existingHenshin.isFile()) {
                    System.setProperty("blocky.henshin", existingHenshin.getAbsolutePath());
                }
            } else {
                String defaultModule = MomotFirstGoalBenchmarkRunner.defaultHenshinModule();
                String fallbackHenshin = firstExisting(
                        "blocky_model/transformations/" + defaultModule,
                        "../blocky_model/transformations/" + defaultModule,
                        defaultModule
                );
                File resolvedHenshin = resolveExistingFile(fallbackHenshin);
                if (resolvedHenshin.exists()) {
                    System.setProperty("blocky.henshin", resolvedHenshin.getAbsolutePath());
                }
            }
        } catch (Exception ignored) {}
    }

    private static Class<?> resolveRunnerClass(ClassLoader cl) throws ClassNotFoundException {
        synchronized (RUNNER_CLASS_LOCK) {
            if (cachedRunnerClass != null && cachedRunnerClassLoader == cl) {
                return cachedRunnerClass;
            }
            try {
                cachedRunnerClass = Class.forName("blocky_momot_runner.blocky_custom", true, cl);
            } catch (Throwable e) {
                cachedRunnerClass = Class.forName("blocky_momot_runner.blocky", true, cl);
            }
            cachedRunnerClassLoader = cl;
            return cachedRunnerClass;
        }
    }

    private static Throwable unwrapInvocationTargetException(Throwable t) {
        Throwable curr = t;
        while (true) {
            if (curr instanceof InvocationTargetException ite && ite.getTargetException() != null) {
                curr = ite.getTargetException();
                continue;
            }
            if (curr.getCause() instanceof InvocationTargetException ite2 && ite2.getTargetException() != null) {
                curr = ite2.getTargetException();
                continue;
            }
            if (curr.getCause() != null && curr != curr.getCause()) {
                String n = curr.getClass().getName();
                if (n.startsWith("java.lang.reflect.")
                        || n.equals("java.lang.RuntimeException")
                        || n.equals("java.lang.Exception")
                        || n.equals("java.lang.Throwable")) {
                    curr = curr.getCause();
                    continue;
                }
            }
            return curr;
        }
    }

    private static String finalizeOutput(RunSpec spec, Consumer<String> onOutputDirReady, Path outputDir, boolean isolatedOutput) {
        try {
            if (isolatedOutput) {
                if (Files.exists(outputDir)) {
                    if (onOutputDirReady != null) onOutputDirReady.accept(outputDir.toString());
                    return outputDir.toString();
                }
                return null;
            }

            File currentDir = new File(System.getProperty("user.dir"));
            Path produced = new File(currentDir, "output").toPath();
            if (Files.exists(produced)) {
                Path target = resolveOutputPath(currentDir, spec.outputBase);
                Files.createDirectories(target.getParent());
                if (Files.exists(target)) deleteDirectoryRecursive(target);
                Files.move(produced, target, StandardCopyOption.REPLACE_EXISTING);
                if (onOutputDirReady != null) onOutputDirReady.accept(target.toString());
                return target.toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> curr = clazz;
        while (curr != null) {
            try { return curr.getDeclaredField(name); }
            catch (NoSuchFieldException e) { curr = curr.getSuperclass(); }
        }
        return null;
    }

    private static void registerPackages(ClassLoader cl, Consumer<String> log) {
        try {
            Class<?> regClass = Class.forName("org.eclipse.emf.ecore.EPackage$Registry", true, cl);
            Class<?> ecorePkgClass = Class.forName("org.eclipse.emf.ecore.EcorePackage", true, cl);
            Class<?> blockyPkgClass = Class.forName("blocky.BlockyPackage", true, cl);
            Object registry = regClass.getField("INSTANCE").get(null);
            Object ecoreInst = ecorePkgClass.getField("eINSTANCE").get(null);
            String ecoreUri = (String) ecorePkgClass.getField("eNS_URI").get(null);
            Object blockyInst = blockyPkgClass.getField("eINSTANCE").get(null);
            String blockyUri = (String) blockyPkgClass.getField("eNS_URI").get(null);
            
            java.util.Map map = (java.util.Map) registry;
            map.put(ecoreUri, ecoreInst);
            map.put(blockyUri, blockyInst);
            map.put(blockyUri + "#", blockyInst);
            
            try {
                regClass.getMethod("put", String.class, Object.class).invoke(registry, blockyUri, blockyInst);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static File resolveExistingFile(String path) {
        if (path == null || path.isBlank()) return new File("model/1.xmi");
        File f = new File(path);
        if (f.isAbsolute() && f.exists()) return f;
        if (f.exists()) return f;
        File f1 = new File("..", path);
        if (f1.exists()) return f1;
        File f2 = new File("blocky_momot", path);
        if (f2.exists()) return f2;
        File f3 = new File("../blocky_momot", path);
        if (f3.exists()) return f3;
        File f4 = new File("blocky_game", path);
        if (f4.exists()) return f4;
        File f5 = new File("../blocky_game", path);
        if (f5.exists()) return f5;
        File f6 = new File("/app", path);
        if (f6.exists()) return f6;
        File f7 = new File("/app/blocky_game", path);
        if (f7.exists()) return f7;
        File f8 = new File("/app/blocky_momot", path);
        if (f8.exists()) return f8;
        File f9 = new File("/app/blocky_model", path);
        if (f9.exists()) return f9;
        if (path.startsWith("../")) {
            String stripped = path.substring(3);
            File s1 = new File(stripped);
            if (s1.exists()) return s1;
            File s2 = new File("/app", stripped);
            if (s2.exists()) return s2;
        }
        return f;
    }

    public static String firstExisting(String... paths) {
        for (String p : paths) {
            if (p != null) {
                File f = resolveExistingFile(p);
                if (f.exists()) return p;
            }
        }
        return paths[0];
    }

    private static String firstWritableParent(String... dirPaths) {
        for (String p : dirPaths) {
            File d = new File(p);
            if ((d.exists() || d.mkdirs()) && d.isDirectory()) return p;
        }
        return dirPaths[0];
    }

    private static void deleteDirectoryRecursive(Path path) {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted((p1, p2) -> p2.compareTo(p1)).map(Path::toFile).forEach(File::delete);
        } catch (Exception ignored) {}
    }

    private static String throwableToString(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private static class ThreadMirroringOutputStream extends OutputStream {
        private static final ThreadLocal<Consumer<String>> THREAD_OUT_LISTENER = new ThreadLocal<>();
        private static final ThreadLocal<Consumer<String>> THREAD_ERR_LISTENER = new ThreadLocal<>();
        private static final ThreadLocal<StringBuilder> THREAD_OUT_BUF = ThreadLocal.withInitial(StringBuilder::new);
        private static final ThreadLocal<StringBuilder> THREAD_ERR_BUF = ThreadLocal.withInitial(StringBuilder::new);
        private static final ThreadLocal<Boolean> IN_CALLBACK = ThreadLocal.withInitial(() -> false);

        private final PrintStream fallback;
        private final boolean isErr;

        public ThreadMirroringOutputStream(PrintStream fallback, boolean isErr) {
            this.fallback = fallback;
            this.isErr = isErr;
        }

        public static void setThreadListener(Consumer<String> outListener, Consumer<String> errListener) {
            THREAD_OUT_LISTENER.set(outListener);
            THREAD_ERR_LISTENER.set(errListener);
        }

        public static void clearThreadListener() {
            flushThreadBuffer(false);
            flushThreadBuffer(true);
            THREAD_OUT_LISTENER.remove();
            THREAD_ERR_LISTENER.remove();
            THREAD_OUT_BUF.remove();
            THREAD_ERR_BUF.remove();
            IN_CALLBACK.remove();
        }

        private static void flushThreadBuffer(boolean isErr) {
            Consumer<String> listener = isErr ? THREAD_ERR_LISTENER.get() : THREAD_OUT_LISTENER.get();
            StringBuilder buf = isErr ? THREAD_ERR_BUF.get() : THREAD_OUT_BUF.get();
            if (listener != null && buf != null && buf.length() > 0 && !IN_CALLBACK.get()) {
                String s = buf.toString();
                buf.setLength(0);
                IN_CALLBACK.set(true);
                try {
                    listener.accept(s);
                } finally {
                    IN_CALLBACK.set(false);
                }
            }
        }

        @Override
        public void write(int b) throws java.io.IOException {
            if (fallback != null) fallback.write(b);
            Consumer<String> listener = isErr ? THREAD_ERR_LISTENER.get() : THREAD_OUT_LISTENER.get();
            if (listener == null) return;
            StringBuilder buf = isErr ? THREAD_ERR_BUF.get() : THREAD_OUT_BUF.get();
            if (b == '\n') {
                String s = buf.toString();
                buf.setLength(0);
                if (!IN_CALLBACK.get()) {
                    IN_CALLBACK.set(true);
                    try {
                        listener.accept(s);
                    } finally {
                        IN_CALLBACK.set(false);
                    }
                }
            } else if (b != '\r') {
                buf.append((char) b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            if (fallback != null) fallback.write(b, off, len);
            Consumer<String> listener = isErr ? THREAD_ERR_LISTENER.get() : THREAD_OUT_LISTENER.get();
            if (listener == null) return;
            StringBuilder buf = isErr ? THREAD_ERR_BUF.get() : THREAD_OUT_BUF.get();
            for (int i = 0; i < len; i++) {
                byte curr = b[off + i];
                if (curr == '\n') {
                    String s = buf.toString();
                    buf.setLength(0);
                    if (!IN_CALLBACK.get()) {
                        IN_CALLBACK.set(true);
                        try {
                            listener.accept(s);
                        } finally {
                            IN_CALLBACK.set(false);
                        }
                    }
                } else if (curr != '\r') {
                    buf.append((char) curr);
                }
            }
        }

        @Override
        public void flush() throws java.io.IOException {
            if (fallback != null) fallback.flush();
            flushThreadBuffer(isErr);
        }
    }
}
