package blocky_game;

import blocky.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameEngineFeatureTest {

    @Test
    void testDetermineStartOrientationExplicit() {
        Level level = BlockyFactory.eINSTANCE.createLevel();
        level.setStartOrientation(Direction.SOUTH);

        Cell startCell = BlockyFactory.eINSTANCE.createCell();
        startCell.setType(CellType.START);

        Direction orientation = SimUtils.determineStartOrientation(level, startCell);
        assertEquals(Direction.SOUTH, orientation);
    }

    @Test
    void testDetermineStartOrientationInferred() {
        Level level = BlockyFactory.eINSTANCE.createLevel();
        // Unset explicit start orientation

        Cell start = BlockyFactory.eINSTANCE.createCell();
        start.setX(0); start.setY(0);
        start.setType(CellType.START);

        Cell right = BlockyFactory.eINSTANCE.createCell();
        right.setX(1); right.setY(0);
        right.setType(CellType.EMPTY);

        start.setRight(right);
        right.setLeft(start);

        Direction orientation = SimUtils.determineStartOrientation(level, start);
        assertEquals(Direction.EAST, orientation);
    }

    @Test
    void testComputeTraceFromStateAtomicMove() {
        Level level = BlockyFactory.eINSTANCE.createLevel();
        GridMap map = BlockyFactory.eINSTANCE.createGridMap();
        level.setMap(map);

        Cell c0 = BlockyFactory.eINSTANCE.createCell();
        c0.setX(0); c0.setY(0); c0.setType(CellType.START);

        Cell c1 = BlockyFactory.eINSTANCE.createCell();
        c1.setX(1); c1.setY(0); c1.setType(CellType.EMPTY);

        Cell c2 = BlockyFactory.eINSTANCE.createCell();
        c2.setX(2); c2.setY(0); c2.setType(CellType.GOAL);

        c0.setRight(c1); c1.setLeft(c0);
        c1.setRight(c2); c2.setLeft(c1);

        map.getCells().add(c0);
        map.getCells().add(c1);
        map.getCells().add(c2);

        // Build solution body: moveForward, moveForward
        Body solution = BlockyFactory.eINSTANCE.createBody();
        level.setSolution(solution);

        Container cont1 = BlockyFactory.eINSTANCE.createContainer();
        AtomicStatement move1 = BlockyFactory.eINSTANCE.createAtomicStatement();
        move1.setKind(AtomicStatementKind.MOVE_FORWARD);
        cont1.setStatement(move1);
        solution.setFirstContainer(cont1);

        Container cont2 = BlockyFactory.eINSTANCE.createContainer();
        AtomicStatement move2 = BlockyFactory.eINSTANCE.createAtomicStatement();
        move2.setKind(AtomicStatementKind.MOVE_FORWARD);
        cont2.setStatement(move2);
        cont1.setNext(cont2);

        DebuggingService.DebugTraceResult res = DebuggingService.computeTraceFromState(level, 0, 0, Direction.EAST);
        assertNotNull(res);
        assertNotNull(res.trace);
        assertTrue(res.trace.getStates().size() >= 3);

        assertEquals(0, res.trace.getStates().get(0).getPosition().getX());
        assertEquals(1, res.trace.getStates().get(1).getPosition().getX());
        assertEquals(2, res.trace.getStates().get(2).getPosition().getX());
        assertEquals(GameStatus.WON, res.trace.getStates().get(2).getStatus());
    }

    @Test
    void testComputeTraceFromStateWithLoopAndIf() {
        Level level = BlockyFactory.eINSTANCE.createLevel();
        GridMap map = BlockyFactory.eINSTANCE.createGridMap();
        level.setMap(map);

        Cell c0 = BlockyFactory.eINSTANCE.createCell();
        c0.setX(0); c0.setY(0); c0.setType(CellType.START);

        Cell c1 = BlockyFactory.eINSTANCE.createCell();
        c1.setX(1); c1.setY(0); c1.setType(CellType.GOAL);

        c0.setRight(c1); c1.setLeft(c0);
        map.getCells().add(c0);
        map.getCells().add(c1);

        // Solution with Loop and IfStmt
        Body solution = BlockyFactory.eINSTANCE.createBody();
        level.setSolution(solution);

        Container cont = BlockyFactory.eINSTANCE.createContainer();
        Loop loop = BlockyFactory.eINSTANCE.createLoop();
        cont.setStatement(loop);
        solution.setFirstContainer(cont);

        Body loopBody = BlockyFactory.eINSTANCE.createBody();
        loop.setBody(loopBody);

        Container loopCont = BlockyFactory.eINSTANCE.createContainer();
        IfStmt ifStmt = BlockyFactory.eINSTANCE.createIfStmt();
        ifStmt.setCondition(ConditionKind.CHECK_FORWARD);
        loopCont.setStatement(ifStmt);
        loopBody.setFirstContainer(loopCont);

        Body thenBody = BlockyFactory.eINSTANCE.createBody();
        ifStmt.setThenBody(thenBody);

        Container thenCont = BlockyFactory.eINSTANCE.createContainer();
        AtomicStatement move = BlockyFactory.eINSTANCE.createAtomicStatement();
        move.setKind(AtomicStatementKind.MOVE_FORWARD);
        thenCont.setStatement(move);
        thenBody.setFirstContainer(thenCont);

        DebuggingService.DebugTraceResult res = DebuggingService.computeTraceFromState(level, 0, 0, Direction.EAST);
        assertNotNull(res);
        assertNotNull(res.trace);
        assertFalse(res.trace.getStates().isEmpty());
    }
}
