package blocky_game;

import blocky.*;
import blocky_momot.BlockyProgramDistance;
import blocky_momot.BlockyProgramMetrics;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ParallelMomotSearchTest {

    @Test
    void testVisualBlockCountAndEditDistance() {
        BlockyFactory factory = BlockyFactory.eINSTANCE;

        // Baseline: [MoveForward, TurnLeft] -> 2 blocks
        Body baseline = factory.createBody();
        Container c1 = factory.createContainer();
        AtomicStatement s1 = factory.createAtomicStatement();
        s1.setKind(AtomicStatementKind.MOVE_FORWARD);
        c1.setStatement(s1);

        Container c2 = factory.createContainer();
        AtomicStatement s2 = factory.createAtomicStatement();
        s2.setKind(AtomicStatementKind.TURN_LEFT);
        c2.setStatement(s2);

        c1.setNext(c2);
        baseline.setFirstContainer(c1);

        assertEquals(2, BlockyProgramMetrics.countStatements(baseline), "Baseline should have 2 blocks");

        // 1. Same statements, but with an empty container inserted in between
        Body withEmptyContainer = factory.createBody();
        Container ec1 = factory.createContainer();
        AtomicStatement es1 = factory.createAtomicStatement();
        es1.setKind(AtomicStatementKind.MOVE_FORWARD);
        ec1.setStatement(es1);

        Container empty = factory.createContainer(); // empty container (statement == null)

        Container ec2 = factory.createContainer();
        AtomicStatement es2 = factory.createAtomicStatement();
        es2.setKind(AtomicStatementKind.TURN_LEFT);
        ec2.setStatement(es2);

        ec1.setNext(empty);
        empty.setNext(ec2);
        withEmptyContainer.setFirstContainer(ec1);

        assertEquals(2, BlockyProgramMetrics.countStatements(withEmptyContainer),
                "Empty container must not count towards block count");
        assertEquals(0, BlockyProgramDistance.programDistance(baseline, withEmptyContainer),
                "Empty container must not increase graph edit distance");

        // 2. Add 1 visual block: [MoveForward, TurnLeft, MoveForward] -> distance 1
        Body withAddedBlock = factory.createBody();
        Container ac1 = factory.createContainer();
        AtomicStatement as1 = factory.createAtomicStatement();
        as1.setKind(AtomicStatementKind.MOVE_FORWARD);
        ac1.setStatement(as1);

        Container ac2 = factory.createContainer();
        AtomicStatement as2 = factory.createAtomicStatement();
        as2.setKind(AtomicStatementKind.TURN_LEFT);
        ac2.setStatement(as2);

        Container ac3 = factory.createContainer();
        AtomicStatement as3 = factory.createAtomicStatement();
        as3.setKind(AtomicStatementKind.MOVE_FORWARD);
        ac3.setStatement(as3);

        ac1.setNext(ac2);
        ac2.setNext(ac3);
        withAddedBlock.setFirstContainer(ac1);

        assertEquals(3, BlockyProgramMetrics.countStatements(withAddedBlock));
        assertEquals(1, BlockyProgramDistance.programDistance(baseline, withAddedBlock),
                "Adding 1 visual block must increment edit distance by 1");

        // 3. Delete 1 visual block: [MoveForward] -> distance 1
        Body withDeletedBlock = factory.createBody();
        Container dc1 = factory.createContainer();
        AtomicStatement ds1 = factory.createAtomicStatement();
        ds1.setKind(AtomicStatementKind.MOVE_FORWARD);
        dc1.setStatement(ds1);
        withDeletedBlock.setFirstContainer(dc1);

        assertEquals(1, BlockyProgramMetrics.countStatements(withDeletedBlock));
        assertEquals(1, BlockyProgramDistance.programDistance(baseline, withDeletedBlock),
                "Deleting 1 visual block must increment edit distance by 1");

        // 4. Change atomic statement kind (relabel): [MoveForward, TurnRight] -> distance 1
        Body withRelabeledBlock = factory.createBody();
        Container rc1 = factory.createContainer();
        AtomicStatement rs1 = factory.createAtomicStatement();
        rs1.setKind(AtomicStatementKind.MOVE_FORWARD);
        rc1.setStatement(rs1);

        Container rc2 = factory.createContainer();
        AtomicStatement rs2 = factory.createAtomicStatement();
        rs2.setKind(AtomicStatementKind.TURN_RIGHT);
        rc2.setStatement(rs2);

        rc1.setNext(rc2);
        withRelabeledBlock.setFirstContainer(rc1);

        assertEquals(2, BlockyProgramMetrics.countStatements(withRelabeledBlock));
        assertEquals(1, BlockyProgramDistance.programDistance(baseline, withRelabeledBlock),
                "Changing atomic statement kind must increment edit distance by 1");

        // 5. IfStmt condition change (relabel) & nested block counting
        // Baseline If: If(CHECK_FORWARD) { MoveForward } else { TurnLeft } -> 3 visual blocks
        Body ifBaseline = factory.createBody();
        Container ifC = factory.createContainer();
        IfStmt ifStmt1 = factory.createIfStmt();
        ifStmt1.setCondition(ConditionKind.CHECK_FORWARD);

        Body thenB1 = factory.createBody();
        Container tc1 = factory.createContainer();
        AtomicStatement ts1 = factory.createAtomicStatement();
        ts1.setKind(AtomicStatementKind.MOVE_FORWARD);
        tc1.setStatement(ts1);
        thenB1.setFirstContainer(tc1);
        ifStmt1.setThenBody(thenB1);

        Body elseB1 = factory.createBody();
        Container elc1 = factory.createContainer();
        AtomicStatement els1 = factory.createAtomicStatement();
        els1.setKind(AtomicStatementKind.TURN_LEFT);
        elc1.setStatement(els1);
        elseB1.setFirstContainer(elc1);
        ifStmt1.setElseBody(elseB1);

        ifC.setStatement(ifStmt1);
        ifBaseline.setFirstContainer(ifC);

        assertEquals(3, BlockyProgramMetrics.countStatements(ifBaseline),
                "IfStmt with 1 then and 1 else block must count as 3 blocks");

        // Modified If: condition changed to CHECK_LEFT -> distance 1
        Body ifModified = factory.createBody();
        Container ifC2 = factory.createContainer();
        IfStmt ifStmt2 = factory.createIfStmt();
        ifStmt2.setCondition(ConditionKind.CHECK_LEFT);

        Body thenB2 = factory.createBody();
        Container tc2 = factory.createContainer();
        AtomicStatement ts2 = factory.createAtomicStatement();
        ts2.setKind(AtomicStatementKind.MOVE_FORWARD);
        tc2.setStatement(ts2);
        thenB2.setFirstContainer(tc2);
        ifStmt2.setThenBody(thenB2);

        Body elseB2 = factory.createBody();
        Container elc2 = factory.createContainer();
        AtomicStatement els2 = factory.createAtomicStatement();
        els2.setKind(AtomicStatementKind.TURN_LEFT);
        elc2.setStatement(els2);
        elseB2.setFirstContainer(elc2);
        ifStmt2.setElseBody(elseB2);

        ifC2.setStatement(ifStmt2);
        ifModified.setFirstContainer(ifC2);

        assertEquals(3, BlockyProgramMetrics.countStatements(ifModified));
        assertEquals(1, BlockyProgramDistance.programDistance(ifBaseline, ifModified),
                "Changing IfStmt condition must increment edit distance by 1");

        // 6. Loop with nested empty container
        Body loopBody = factory.createBody();
        Container lc1 = factory.createContainer();
        Loop loop = factory.createLoop();
        Body loopInner = factory.createBody();
        Container innerC1 = factory.createContainer();
        AtomicStatement is1 = factory.createAtomicStatement();
        is1.setKind(AtomicStatementKind.MOVE_FORWARD);
        innerC1.setStatement(is1);
        Container innerEmpty = factory.createContainer();
        innerC1.setNext(innerEmpty);
        loopInner.setFirstContainer(innerC1);
        loop.setBody(loopInner);
        lc1.setStatement(loop);
        loopBody.setFirstContainer(lc1);

        assertEquals(2, BlockyProgramMetrics.countStatements(loopBody),
                "Loop with 1 inner statement (plus empty container) must count as 2 blocks");
    }

    @Test
    void testBlockyProgramDistanceThreadLocalIsolation() throws Exception {
        BlockyFactory factory = BlockyFactory.eINSTANCE;

        // Create baseline 1: [MoveForward, MoveForward]
        Game game1 = factory.createGame();
        Level level1 = factory.createLevel();
        Body body1 = factory.createBody();
        Container c1 = factory.createContainer();
        AtomicStatement s1 = factory.createAtomicStatement();
        s1.setKind(AtomicStatementKind.MOVE_FORWARD);
        c1.setStatement(s1);
        Container c2 = factory.createContainer();
        AtomicStatement s2 = factory.createAtomicStatement();
        s2.setKind(AtomicStatementKind.MOVE_FORWARD);
        c2.setStatement(s2);
        c1.setNext(c2);
        body1.setFirstContainer(c1);
        level1.setSolution(body1);
        game1.getLevels().add(level1);

        // Create baseline 2: [TurnLeft, TurnLeft]
        Game game2 = factory.createGame();
        Level level2 = factory.createLevel();
        Body body2 = factory.createBody();
        Container c3 = factory.createContainer();
        AtomicStatement s3 = factory.createAtomicStatement();
        s3.setKind(AtomicStatementKind.TURN_LEFT);
        c3.setStatement(s3);
        Container c4 = factory.createContainer();
        AtomicStatement s4 = factory.createAtomicStatement();
        s4.setKind(AtomicStatementKind.TURN_LEFT);
        c4.setStatement(s4);
        c3.setNext(c4);
        body2.setFirstContainer(c3);
        level2.setSolution(body2);
        game2.getLevels().add(level2);

        // Target program to evaluate: [MoveForward]
        Game testGame = factory.createGame();
        Level testLevel = factory.createLevel();
        Body testBody = factory.createBody();
        Container cTest = factory.createContainer();
        AtomicStatement sTest = factory.createAtomicStatement();
        sTest.setKind(AtomicStatementKind.MOVE_FORWARD);
        cTest.setStatement(sTest);
        testBody.setFirstContainer(cTest);
        testLevel.setSolution(testBody);
        testGame.getLevels().add(testLevel);

        // Expected distance:
        // Against baseline 1 ([MF, MF] vs [MF]): distance is 1 (1 deletion)
        // Against baseline 2 ([TL, TL] vs [MF]): distance is 2 (1 relabel + 1 delete)

        AtomicInteger distThread1 = new AtomicInteger(-1);
        AtomicInteger distThread2 = new AtomicInteger(-1);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(2);

        Thread t1 = new Thread(() -> {
            try {
                startLatch.await();
                BlockyProgramDistance.setThreadBaseline(body1);
                // Busy wait / sleep slightly to ensure concurrency
                Thread.sleep(50);
                distThread1.set(BlockyProgramDistance.distanceToBaseline(testGame));
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                BlockyProgramDistance.clearThreadBaseline();
                finishLatch.countDown();
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                startLatch.await();
                BlockyProgramDistance.setThreadBaseline(body2);
                Thread.sleep(50);
                distThread2.set(BlockyProgramDistance.distanceToBaseline(testGame));
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                BlockyProgramDistance.clearThreadBaseline();
                finishLatch.countDown();
            }
        });

        t1.start();
        t2.start();
        startLatch.countDown();

        assertTrue(finishLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, distThread1.get(), "Thread 1 should measure distance to baseline 1 ([MF, MF]) as 1");
        assertEquals(2, distThread2.get(), "Thread 2 should measure distance to baseline 2 ([TL, TL]) as 2");

        // Main thread has no thread-local baseline; ensure no sticky global baseline is used
        assertEquals(100000, BlockyProgramDistance.distanceToBaseline(testGame),
                "Caller thread without baseline must not see any sticky global baseline");
    }

    @Test
    void testMomotFitnessObjectiveHelperThreadIsolation() throws Exception {
        String prevObj = System.getProperty("blocky.objectives");
        System.setProperty("blocky.objectives", "CURRENT");
        try {
            BlockyFactory factory = BlockyFactory.eINSTANCE;

            // Baseline A: [MoveForward, MoveForward]
            Game gameA = factory.createGame();
            Level lvlA = factory.createLevel();
            Body bodyA = factory.createBody();
            Container ca1 = factory.createContainer();
            AtomicStatement sa1 = factory.createAtomicStatement();
            sa1.setKind(AtomicStatementKind.MOVE_FORWARD);
            ca1.setStatement(sa1);
            Container ca2 = factory.createContainer();
            AtomicStatement sa2 = factory.createAtomicStatement();
            sa2.setKind(AtomicStatementKind.MOVE_FORWARD);
            ca2.setStatement(sa2);
            ca1.setNext(ca2);
            bodyA.setFirstContainer(ca1);
            lvlA.setSolution(bodyA);
            gameA.getLevels().add(lvlA);

            // Baseline B: [TurnLeft, TurnLeft]
            Game gameB = factory.createGame();
            Level lvlB = factory.createLevel();
            Body bodyB = factory.createBody();
            Container cb1 = factory.createContainer();
            AtomicStatement sb1 = factory.createAtomicStatement();
            sb1.setKind(AtomicStatementKind.TURN_LEFT);
            cb1.setStatement(sb1);
            Container cb2 = factory.createContainer();
            AtomicStatement sb2 = factory.createAtomicStatement();
            sb2.setKind(AtomicStatementKind.TURN_LEFT);
            cb2.setStatement(sb2);
            cb1.setNext(cb2);
            bodyB.setFirstContainer(cb1);
            lvlB.setSolution(bodyB);
            gameB.getLevels().add(lvlB);

            // Candidate model: [MoveForward]
            Game candidateGame = factory.createGame();
            Level candLvl = factory.createLevel();
            Body candBody = factory.createBody();
            Container cc1 = factory.createContainer();
            AtomicStatement sc1 = factory.createAtomicStatement();
            sc1.setKind(AtomicStatementKind.MOVE_FORWARD);
            cc1.setStatement(sc1);
            candBody.setFirstContainer(cc1);
            candLvl.setSolution(candBody);
            candidateGame.getLevels().add(candLvl);

            class TestBlockyRunner extends blocky_momot_runner.blocky_custom {
                double evalFitness(Game g) {
                    return _createObjectiveHelper_1(null, null, g);
                }
            }

            TestBlockyRunner runnerA = new TestBlockyRunner();
            runnerA.setBaselineSolution(bodyA);

            TestBlockyRunner runnerB = new TestBlockyRunner();
            runnerB.setBaselineSolution(bodyB);

            AtomicReference<Double> scoreA = new AtomicReference<>();
            AtomicReference<Double> scoreB = new AtomicReference<>();
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            Thread ta = new Thread(() -> {
                try {
                    startLatch.await();
                    scoreA.set(runnerA.evalFitness(candidateGame));
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });

            Thread tb = new Thread(() -> {
                try {
                    startLatch.await();
                    scoreB.set(runnerB.evalFitness(candidateGame));
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });

            ta.start();
            tb.start();
            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

            assertEquals(1.0, scoreA.get(), "Runner A against [MF, MF] should give edit distance 1.0");
            assertEquals(2.0, scoreB.get(), "Runner B against [TL, TL] should give edit distance 2.0");

            // Verify unconfigured runner yields penalty without sticky global baseline
            TestBlockyRunner unconfiguredRunner = new TestBlockyRunner();
            assertEquals(100000.0, unconfiguredRunner.evalFitness(candidateGame),
                    "Unconfigured runner without baseline must return fallback penalty, not a sticky baseline");
        } finally {
            if (prevObj != null) {
                System.setProperty("blocky.objectives", prevObj);
            } else {
                System.clearProperty("blocky.objectives");
            }
        }
    }

    @Test
    void testThreadLocalStdoutCapture() throws Exception {
        MomotRunService.ensureSystemStreamsInstalled();

        List<String> logs1 = Collections.synchronizedList(new ArrayList<>());
        List<String> logs2 = Collections.synchronizedList(new ArrayList<>());

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(2);

        // Run two concurrent specs
        MomotRunService.RunSpec spec1 = new MomotRunService.RunSpec("model/input/1.xmi", "target/test_out_stream_1", 20, 100, 1, 4);
        MomotRunService.RunSpec spec2 = new MomotRunService.RunSpec("model/input/1.xmi", "target/test_out_stream_2", 20, 100, 1, 4);

        Thread at1 = MomotRunService.runAsync(spec1, logs1::add, finishLatch::countDown, null);
        Thread at2 = MomotRunService.runAsync(spec2, logs2::add, finishLatch::countDown, null);

        assertNotNull(at1);
        assertNotNull(at2);
        assertNotEquals(at1, at2, "Concurrent runAsync must produce separate threads");

        assertTrue(finishLatch.await(30, TimeUnit.SECONDS));

        assertFalse(logs1.isEmpty(), "Session 1 should have received stdout lines");
        assertFalse(logs2.isEmpty(), "Session 2 should have received stdout lines");
    }

    @Test
    void testConcurrentHostedSearchesOnDifferentPrograms() throws Exception {
        SessionContext session1 = new SessionContext("test-diff-prog-1");
        SessionContext session2 = new SessionContext("test-diff-prog-2");

        session1.syncModel("<xml><block type=\"maze_moveForward\"></block></xml>");
        session2.syncModel("<xml><block type=\"maze_turn\"><field name=\"DIR\">turnLeft</field></block></xml>");

        session1.runMomotWithParams(1, 20, 100, 1, 4);
        session2.runMomotWithParams(2, 20, 100, 1, 4);

        long start = System.currentTimeMillis();
        while ((session1.isMomotRunning() || session2.isMomotRunning()) && System.currentTimeMillis() - start < 30000) {
            Thread.sleep(100);
        }

        assertFalse(session1.isMomotRunning(), "Session 1 should have finished");
        assertFalse(session2.isMomotRunning(), "Session 2 should have finished");
        assertEquals("Finished", session1.getMomotStatus());
        assertEquals("Finished", session2.getMomotStatus());

        assertNotNull(session1.getMomotCurrentOutputDir());
        assertNotNull(session2.getMomotCurrentOutputDir());
        assertNotEquals(session1.getMomotCurrentOutputDir(), session2.getMomotCurrentOutputDir());

        List<MomotResultsService.SolutionEntry> sols1 = session1.listMomotSolutions();
        List<MomotResultsService.SolutionEntry> sols2 = session2.listMomotSolutions();

        assertNotNull(sols1);
        assertNotNull(sols2);
    }

    @Test
    void testStopOneSessionDoesNotAffectAnother() throws Exception {
        SessionContext session1 = new SessionContext("test-parallel-1");
        SessionContext session2 = new SessionContext("test-parallel-2");

        // Start searches with higher eval count so they take some time
        session1.runMomotWithParams(1, 100, 5000, 5, 10);
        session2.runMomotWithParams(2, 100, 5000, 5, 10);

        assertTrue(session1.isMomotRunning() || "Waiting".equals(session1.getMomotStatus()) || "Running".equals(session1.getMomotStatus()));
        assertTrue(session2.isMomotRunning() || "Waiting".equals(session2.getMomotStatus()) || "Running".equals(session2.getMomotStatus()));

        // Stop session 1 only
        session1.stopMomotRun();

        assertFalse(session1.isMomotRunning());
        assertEquals("Stopped", session1.getMomotStatus());

        // Verify session 2 was NOT stopped and is still active
        assertTrue(session2.isMomotRunning(), "Session 2 should still be running after Session 1 was stopped");
        assertNotEquals("Stopped", session2.getMomotStatus());

        // Clean up session 2
        session2.stopMomotRun();
        assertFalse(session2.isMomotRunning());
        assertEquals("Stopped", session2.getMomotStatus());
    }

    @Test
    void testSemaphoreWaitingQueueAndStateTransitions() throws Exception {
        List<SessionContext> sessions = new ArrayList<>();
        // Start 11 sessions with higher eval count so they do not finish instantaneously
        for (int i = 0; i < 11; i++) {
            SessionContext sc = new SessionContext("queue-test-" + i);
            sessions.add(sc);
            sc.runMomotWithParams(i, 50, 5000, 2, 8);
        }

        // Give a short moment for threads to initialize and enter semaphore
        Thread.sleep(100);

        int runningCount = 0;
        int waitingCount = 0;
        int finishedCount = 0;
        for (SessionContext sc : sessions) {
            String st = sc.getMomotStatus();
            if ("Running".equals(st)) {
                runningCount++;
            } else if ("Waiting".equals(st)) {
                waitingCount++;
            } else if ("Finished".equals(st)) {
                finishedCount++;
            }
        }

        // Up to 10 can be running, at least 1 should be waiting if 10 slots filled
        assertTrue(runningCount <= 10, "At most 10 sessions can be running simultaneously, was: " + runningCount);
        assertTrue(runningCount + waitingCount + finishedCount == 11, "All 11 sessions should be tracked in valid states");

        // Stop all sessions to clean up
        for (SessionContext sc : sessions) {
            sc.stopMomotRun();
        }
    }

    @Test
    void testLevelSwitchDoesNotShowPreviousSearchResults() throws Exception {
        SessionContext session = new SessionContext("test-level-switch-" + System.nanoTime());
        assertEquals(1, session.getEngine().getCurrentLevel().getId());

        session.runMomotWithParams(1, 20, 400, 1, 4);
        long started = System.currentTimeMillis();
        while (!"Running".equals(session.getMomotStatus()) && System.currentTimeMillis() - started < 10000) {
            Thread.sleep(50);
        }
        String level1Dir = session.getMomotCurrentOutputDir();
        assertNotNull(level1Dir, "Level 1 search should have an output directory");
        File level1RunDir = new File(level1Dir).getParentFile();
        assertTrue(new File(level1RunDir, "input.xmi").isFile(), "Level 1 input must be stored with its run");

        session.syncLevelMeta("{\"level\":2,\"maxBlocks\":5,\"startDirection\":1}");
        assertEquals(2, session.getEngine().getCurrentLevel().getId());

        long quietUntil = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < quietUntil) {
            assertNull(session.getMomotCurrentOutputDir(), "Level switch should drop the previous output directory");
            assertEquals("Idle", session.getMomotStatus());
            assertFalse(session.isMomotRunning());
            assertTrue(session.listMomotSolutions().isEmpty());
            Thread.sleep(100);
        }

        session.runMomotWithParams(2, 20, 100, 1, 4);
        String level2Dir = session.getMomotCurrentOutputDir();
        assertNotNull(level2Dir);
        assertFalse(sameFile(level1Dir, level2Dir), "Level 2 must not reuse the level 1 output directory");
        File level2RunDir = new File(level2Dir).getParentFile();
        assertTrue(new File(level2RunDir, "input.xmi").isFile(), "Level 2 input must be stored with its run");
        assertFalse(sameFile(
                new File(level1RunDir, "input.xmi").getAbsolutePath(),
                new File(level2RunDir, "input.xmi").getAbsolutePath()));

        long deadline = System.currentTimeMillis() + 40000;
        boolean level2Finished = false;
        while (System.currentTimeMillis() < deadline) {
            String currentDir = session.getMomotCurrentOutputDir();
            assertNotNull(currentDir);
            assertFalse(sameFile(level1Dir, currentDir), "A finished level 1 search restored its output directory");
            if (!session.isMomotRunning() && "Finished".equals(session.getMomotStatus())) {
                level2Finished = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(level2Finished, "Level 2 search should finish. Status was " + session.getMomotStatus());

        long watchUntil = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < watchUntil) {
            assertFalse(sameFile(level1Dir, session.getMomotCurrentOutputDir()));
            assertEquals("Finished", session.getMomotStatus());
            assertEquals(2, session.getEngine().getCurrentLevel().getId());
            Thread.sleep(200);
        }

        List<MomotResultsService.SolutionEntry> sols = session.listMomotSolutions();
        assertFalse(sols.isEmpty(), "Level 2 search should produce solutions");
        String level1RunName = level1RunDir.getName();
        boolean sawLevel2Model = false;
        for (MomotResultsService.SolutionEntry entry : sols) {
            String modelPath = entry.modelPath == null ? "" : entry.modelPath;
            String outputDir = entry.outputDir == null ? "" : entry.outputDir;
            assertFalse(modelPath.contains(level1RunName), "Solution model came from the previous level: " + modelPath);
            assertFalse(outputDir.contains(level1RunName), "Solution output came from the previous level: " + outputDir);
            assertTrue(outputDir.contains(level2RunDir.getName()) || sameFile(level2Dir, outputDir));
            if (!modelPath.isEmpty() && new File(modelPath).isFile()) {
                String xml = java.nio.file.Files.readString(new File(modelPath).toPath());
                assertFalse(xml.contains("title=\"Maze Level 1\""), "Loaded a level 1 model: " + modelPath);
                if (xml.contains("title=\"Maze Level 2\"")) {
                    sawLevel2Model = true;
                }
            }
        }
        assertTrue(sawLevel2Model, "Listed solutions should include a level 2 model");
    }

    @Test
    void testParallelRunEditDistancesMatchSoloRuns() throws Exception {
        String prog1Xml = "<xml><block type=\"maze_moveForward\"></block></xml>";
        String prog2Xml = "<xml><block type=\"maze_turn\"><field name=\"DIR\">turnLeft</field></block></xml>";
        int seed1 = 1;
        int seed2 = 2;

        // Step 1: Run Program 1 solo with seed 1
        SessionContext solo1 = new SessionContext("solo-prog-1-" + System.nanoTime());
        solo1.syncModel(prog1Xml);
        solo1.runMomotWithParams(seed1, 20, 100, 1, 4);
        long wait1 = System.currentTimeMillis();
        while (solo1.isMomotRunning() && System.currentTimeMillis() - wait1 < 30000) {
            Thread.sleep(50);
        }
        assertFalse(solo1.isMomotRunning(), "Solo 1 should finish");
        List<Double> soloEdits1 = extractEditDistances(solo1.getMomotCurrentOutputDir());
        assertFalse(soloEdits1.isEmpty(), "Solo 1 should produce edit distances");

        // Step 2: Run Program 2 solo with seed 2
        SessionContext solo2 = new SessionContext("solo-prog-2-" + System.nanoTime());
        solo2.syncModel(prog2Xml);
        solo2.runMomotWithParams(seed2, 20, 100, 1, 4);
        long wait2 = System.currentTimeMillis();
        while (solo2.isMomotRunning() && System.currentTimeMillis() - wait2 < 30000) {
            Thread.sleep(50);
        }
        assertFalse(solo2.isMomotRunning(), "Solo 2 should finish");
        List<Double> soloEdits2 = extractEditDistances(solo2.getMomotCurrentOutputDir());
        assertFalse(soloEdits2.isEmpty(), "Solo 2 should produce edit distances");

        // Step 3: Run Program 1 (seed 1) and Program 2 (seed 2) simultaneously in parallel
        SessionContext par1 = new SessionContext("par-prog-1-" + System.nanoTime());
        SessionContext par2 = new SessionContext("par-prog-2-" + System.nanoTime());
        par1.syncModel(prog1Xml);
        par2.syncModel(prog2Xml);

        par1.runMomotWithParams(seed1, 20, 100, 1, 4);
        par2.runMomotWithParams(seed2, 20, 100, 1, 4);

        long waitPar = System.currentTimeMillis();
        while ((par1.isMomotRunning() || par2.isMomotRunning()) && System.currentTimeMillis() - waitPar < 30000) {
            Thread.sleep(50);
        }
        assertFalse(par1.isMomotRunning(), "Parallel 1 should finish");
        assertFalse(par2.isMomotRunning(), "Parallel 2 should finish");

        List<Double> parEdits1 = extractEditDistances(par1.getMomotCurrentOutputDir());
        List<Double> parEdits2 = extractEditDistances(par2.getMomotCurrentOutputDir());

        // Verify that parallel searches with different baseline programs produce identical
        // edit distances to serialized solo runs of each respective program.
        assertEquals(soloEdits1, parEdits1, "Parallel run for Program 1 must produce identical edit distances to solo run");
        assertEquals(soloEdits2, parEdits2, "Parallel run for Program 2 must produce identical edit distances to solo run");
        assertNotEquals(soloEdits1, soloEdits2, "Program 1 and Program 2 must have different edit distance sets");

        // Verify that all 5 objectives [GoalReached, Edits, Actions, closestToGoal, Blocks] match between solo and parallel runs
        List<List<Double>> soloObjectives1 = extractAllObjectives(solo1.getMomotCurrentOutputDir());
        List<List<Double>> soloObjectives2 = extractAllObjectives(solo2.getMomotCurrentOutputDir());
        List<List<Double>> parObjectives1 = extractAllObjectives(par1.getMomotCurrentOutputDir());
        List<List<Double>> parObjectives2 = extractAllObjectives(par2.getMomotCurrentOutputDir());

        assertFalse(soloObjectives1.isEmpty(), "Solo 1 must produce objective points");
        assertFalse(soloObjectives2.isEmpty(), "Solo 2 must produce objective points");
        assertEquals(soloObjectives1, parObjectives1, "All 5 objectives for Program 1 in parallel run must match solo run");
        assertEquals(soloObjectives2, parObjectives2, "All 5 objectives for Program 2 in parallel run must match solo run");
        assertNotEquals(soloObjectives1, soloObjectives2, "Program 1 and Program 2 must have different objective sets");
    }

    @Test
    void testDesktopStopDoesNotCancelHostedSession() throws Exception {
        SessionContext hostedSession = new SessionContext("hosted-stop-isolation-test-" + System.nanoTime());
        hostedSession.runMomotWithParams(42, 100, 10000, 5, 10);

        assertTrue(hostedSession.isMomotRunning() || "Waiting".equals(hostedSession.getMomotStatus()) || "Running".equals(hostedSession.getMomotStatus()));

        // Simulate desktop Stop button or level change in BlockyUI
        MomotRunService.stopCurrentRun();

        // Hosted session must NOT be stopped by desktop stopCurrentRun
        assertTrue(hostedSession.isMomotRunning(), "Hosted session must NOT be cancelled by desktop stopCurrentRun()");
        assertNotEquals("Stopped", hostedSession.getMomotStatus(), "Hosted session status must not be Stopped");

        // Clean up hosted session
        hostedSession.stopMomotRun();
        assertFalse(hostedSession.isMomotRunning(), "Targeted stopMomotRun should stop the session");
        assertEquals("Stopped", hostedSession.getMomotStatus(), "Session status should be Stopped");
    }

    private static List<List<Double>> extractAllObjectives(String outputDir) throws Exception {
        if (outputDir == null) return Collections.emptyList();
        File pf = new File(outputDir, "objectives.pf");
        if (!pf.isFile()) return Collections.emptyList();
        List<String> lines = java.nio.file.Files.readAllLines(pf.toPath());
        List<List<Double>> all = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+");
            List<Double> row = new ArrayList<>();
            for (String part : parts) {
                row.add(Double.parseDouble(part));
            }
            if (!row.isEmpty()) {
                all.add(row);
            }
        }
        return all;
    }

    private static List<Double> extractEditDistances(String outputDir) throws Exception {
        if (outputDir == null) return Collections.emptyList();
        File pf = new File(outputDir, "objectives.pf");
        if (!pf.isFile()) return Collections.emptyList();
        List<String> lines = java.nio.file.Files.readAllLines(pf.toPath());
        List<Double> edits = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2) {
                edits.add(Double.parseDouble(parts[1]));
            }
        }
        return edits;
    }

    private static boolean sameFile(String left, String right) throws Exception {
        if (left == null || right == null) return false;
        return new File(left).getCanonicalPath().equals(new File(right).getCanonicalPath());
    }

    @Test
    void testAllowedEmptySyncClearsStatements() {
        SessionContext session = new SessionContext("empty-sync-" + System.nanoTime());
        session.syncModel(1, "<xml><block type=\"maze_moveForward\"></block></xml>", false);
        Body sol = session.getEngine().getCurrentLevel().getSolution();
        assertNotNull(sol);
        assertEquals(1, BlockyProgramMetrics.countStatements(sol));

        // When allowEmpty is false, empty XML is ignored and solution remains
        boolean ignored = session.syncModel(1, "<xml></xml>", false);
        assertTrue(ignored);
        assertEquals(1, BlockyProgramMetrics.countStatements(session.getEngine().getCurrentLevel().getSolution()),
                "Ignored empty sync must not clear solution");

        // When allowEmpty is true, empty XML clears the solution
        boolean cleared = session.syncModel(1, "<xml></xml>", true);
        assertTrue(cleared);
        Body clearedSol = session.getEngine().getCurrentLevel().getSolution();
        assertTrue(clearedSol == null || BlockyProgramMetrics.countStatements(clearedSol) == 0,
                "Allowed empty sync must leave no statements");
    }

    @Test
    void testLevelChangePlusRunWritesCleanInputXmi() throws Exception {
        SessionContext session = new SessionContext("clean-run-" + System.nanoTime());
        // Level 1 has a statement
        session.syncModel(1, "<xml><block type=\"maze_moveForward\"></block></xml>", false);
        assertEquals(1, BlockyProgramMetrics.countStatements(session.getEngine().getCurrentLevel().getSolution()));

        // Next level (level 2) with new map, meta, and empty XML snapshot on run
        String map2 = "[[0,0,0],[2,1,3],[0,0,0]]";
        String meta2 = "{\"level\":2,\"maxBlocks\":10,\"startDirection\":1}";
        String emptyXml = "<xml></xml>";
        long epoch2 = 2;

        session.runMomotWithParams(epoch2, map2, meta2, emptyXml, 1, 20, 100, 1, 4);

        String runDir = session.getMomotCurrentOutputDir();
        assertNotNull(runDir);
        File inputXmi = new File(new File(runDir).getParentFile(), "input.xmi");
        assertTrue(inputXmi.isFile(), "input.xmi must exist for level 2 run");
        String content = java.nio.file.Files.readString(inputXmi.toPath());
        assertFalse(content.contains("kind=\"moveForward\""),
                "input.xmi for level 2 run must not contain previous level statements");

        assertEquals(2, session.getEngine().getCurrentLevel().getId());
        Body sol2 = session.getEngine().getCurrentLevel().getSolution();
        assertTrue(sol2 == null || BlockyProgramMetrics.countStatements(sol2) == 0,
                "Level 2 solution must have 0 statements");

        session.stopMomotRun();
    }

    @Test
    void testOlderEpochDoesNotRestoreOldProgram() {
        SessionContext session = new SessionContext("epoch-test-" + System.nanoTime());
        String prog1Xml = "<xml><block type=\"maze_moveForward\"></block></xml>";

        // Page load 1 (epoch 1)
        assertTrue(session.syncModel(1, prog1Xml, false));
        assertEquals(1, session.getLastAppliedEpoch());
        assertEquals(1, BlockyProgramMetrics.countStatements(session.getEngine().getCurrentLevel().getSolution()));

        // Page load 2 (epoch 2) clears workspace
        assertTrue(session.syncModel(2, "<xml></xml>", true));
        assertEquals(2, session.getLastAppliedEpoch());
        Body sol = session.getEngine().getCurrentLevel().getSolution();
        assertTrue(sol == null || BlockyProgramMetrics.countStatements(sol) == 0);

        // Stale sync from page load 1 (epoch 1) arrives late
        assertFalse(session.syncModel(1, prog1Xml, false), "Stale epoch sync must be rejected");
        assertEquals(2, session.getLastAppliedEpoch());
        Body afterStaleSync = session.getEngine().getCurrentLevel().getSolution();
        assertTrue(afterStaleSync == null || BlockyProgramMetrics.countStatements(afterStaleSync) == 0,
                "Stale sync must not restore statements");

        // Stale snapshot from epoch 1 arrives late
        assertFalse(session.applySnapshot(1, "[[2,3]]", "{\"level\":1}", prog1Xml, false),
                "Stale epoch snapshot must be rejected");

        // Stale run from epoch 1 arrives late
        session.runMomotWithParams(1, "[[2,3]]", "{\"level\":1}", prog1Xml, 1, 20, 100, 1, 4);
        assertFalse(session.isMomotRunning(), "Stale epoch run must not start search");
    }
}
