package blocky_game;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages active guest user sessions, supporting session creation, retrieval,
 * and automatic cleanup of inactive sessions.
 */
public class SessionManager {

    private static final SessionManager INSTANCE = new SessionManager();
    private static final long SESSION_TIMEOUT_MS = 2 * 60 * 60 * 1000L; // 2 hours

    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SessionCleanupThread");
        t.setDaemon(true);
        return t;
    });

    private SessionManager() {
        cleanupExecutor.scheduleAtFixedRate(this::evictInactiveSessions, 15, 15, TimeUnit.MINUTES);
    }

    public static SessionManager getInstance() {
        return INSTANCE;
    }

    public SessionContext getOrCreateSession(String sessionId) {
        String idToUse = (sessionId != null && !sessionId.trim().isEmpty())
                ? sessionId.trim()
                : UUID.randomUUID().toString();

        SessionContext ctx = sessions.get(idToUse);
        if (ctx == null) {
            ctx = new SessionContext(idToUse);
            SessionContext existing = sessions.putIfAbsent(idToUse, ctx);
            if (existing != null) {
                ctx = existing;
            } else {
                System.out.println("[SessionManager] Created new session: " + idToUse);
            }
        }
        ctx.touch();
        return ctx;
    }

    public SessionContext getSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        SessionContext ctx = sessions.get(sessionId.trim());
        if (ctx != null) {
            ctx.touch();
        }
        return ctx;
    }

    private void evictInactiveSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            boolean inactive = (now - entry.getValue().getLastAccessedTime()) > SESSION_TIMEOUT_MS;
            if (inactive) {
                System.out.println("[SessionManager] Evicted inactive session: " + entry.getKey());
            }
            return inactive;
        });
    }
}
