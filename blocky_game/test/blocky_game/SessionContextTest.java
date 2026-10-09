package blocky_game;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionContextTest {

    @Test
    void testSessionCreationAndDirectory() {
        SessionManager manager = SessionManager.getInstance();
        SessionContext session = manager.getOrCreateSession(null);

        assertNotNull(session);
        assertNotNull(session.getSessionId());
        assertFalse(session.getSessionId().trim().isEmpty());

        File sessionDir = session.getSessionDir();
        assertNotNull(sessionDir);
        assertTrue(sessionDir.exists());
        assertTrue(sessionDir.isDirectory());
        assertTrue(sessionDir.getName().contains(session.getSessionId()));

        assertNotNull(session.getEngine());
    }

    @Test
    void testTouchAndLastAccessedTime() throws InterruptedException {
        SessionContext session = new SessionContext("test-touch-session");
        long t1 = session.getLastAccessedTime();
        Thread.sleep(10);
        session.touch();
        long t2 = session.getLastAccessedTime();

        assertTrue(t2 >= t1);
    }

    @Test
    void testMomotLogAndStatusDefaults() {
        SessionContext session = new SessionContext("test-momot-defaults");
        assertFalse(session.isMomotRunning());
        assertEquals("Idle", session.getMomotStatus());

        List<String> logs = session.getMomotLogs();
        assertNotNull(logs);
        assertTrue(logs.isEmpty());
    }
}
