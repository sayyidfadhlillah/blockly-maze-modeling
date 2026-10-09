package blocky_game;

import blocky.Cell;
import blocky.Direction;
import blocky.Game;
import blocky.Level;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded HTTP server delivering REST APIs for multi-user web clients and
 * serving static web frontend assets from blockly-games-web.
 */
public class HttpSearchServer {

    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) throws IOException {
        try {
            javafx.application.Platform.startup(() -> {});
        } catch (Throwable ignored) {}

        int port = DEFAULT_PORT;
        String portProp = System.getProperty("http.port");
        if (portProp == null) {
            portProp = System.getenv("PORT");
        }
        if (portProp != null && !portProp.trim().isEmpty()) {
            try {
                port = Integer.parseInt(portProp.trim());
            } catch (NumberFormatException ignored) {}
        }
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        startServer(port);
    }

    public static HttpServer startServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));

        // Register REST API handlers
        server.createContext("/api/session/new", new NewSessionHandler());
        server.createContext("/api/session/state", new SessionStateHandler());
        server.createContext("/api/session/sync", new SyncModelHandler());
        server.createContext("/api/session/map", new SyncMapHandler());
        server.createContext("/api/session/meta", new SyncMetaHandler());
        server.createContext("/api/session/snapshot", new SyncSnapshotHandler());

        server.createContext("/api/momot/run", new MomotRunHandler());
        server.createContext("/api/momot/stop", new MomotStopHandler());
        server.createContext("/api/momot/status", new MomotStatusHandler());
        server.createContext("/api/momot/solutions", new MomotSolutionsHandler());
        server.createContext("/api/momot/solutionPath", new MomotSolutionPathHandler());
        server.createContext("/api/momot/load", new MomotLoadHandler());

        server.createContext("/api/debug/start", new DebugStartHandler());
        server.createContext("/api/debug/step", new DebugStepHandler());
        server.createContext("/api/debug/pause", new DebugPauseHandler());
        server.createContext("/api/debug/stop", new DebugStopHandler());
        server.createContext("/api/debug/skip", new DebugSkipHandler());
        server.createContext("/api/debug/tick", new DebugTickHandler());

        server.createContext("/api/dm/request", new DirectManipulationHandler());
        server.createContext("/api/simulation/run", new SimulationRunHandler());
        server.createContext("/api/level-time", new LevelTimeHandler());

        server.createContext("/api/admin/login", new AdminLoginHandler());
        server.createContext("/api/admin/sessions", new AdminSessionsHandler());
        server.createContext("/api/admin/export", new AdminExportHandler());

        // Static files handler (serves blockly-games-web)
        server.createContext("/", new StaticFileHandler());

        server.start();
        System.out.println("[HttpSearchServer] Server started and listening on http://0.0.0.0:" + server.getAddress().getPort() + "/");
        return server;
    }

    // --- Helper Methods ---

    private static void sendCorsAndNoContent(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, X-Session-ID, Authorization");
        exchange.sendResponseHeaders(204, -1);
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, X-Session-ID, Authorization");

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) || "HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            return "";
        }
        try (InputStream is = exchange.getRequestBody();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    private static SessionContext getSession(HttpExchange exchange, String requestBody) {
        String sessionId = null;
        if (exchange.getRequestHeaders() != null) {
            for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
                if ("x-session-id".equalsIgnoreCase(entry.getKey())) {
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        sessionId = entry.getValue().get(0);
                        break;
                    }
                }
            }
        }
        if (sessionId == null || sessionId.trim().isEmpty()) {
            // Try query param
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("sessionId=")) {
                for (String param : query.split("&")) {
                    if (param.startsWith("sessionId=")) {
                        sessionId = param.substring("sessionId=".length());
                        break;
                    }
                }
            }
        }
        if ((sessionId == null || sessionId.trim().isEmpty()) && requestBody != null) {
            sessionId = parseJsonField(requestBody, "sessionId");
        }
        SessionContext session = SessionManager.getInstance().getOrCreateSession(sessionId);
        if (exchange.getResponseHeaders() != null) {
            exchange.getResponseHeaders().set("X-Session-ID", session.getSessionId());
        }
        return session;
    }

    private static String parseJsonField(String json, String field) {
        if (json == null || json.trim().isEmpty()) return null;
        Pattern pStr = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher mStr = pStr.matcher(json);
        if (mStr.find()) {
            String raw = mStr.group(1);
            return raw.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
        }

        Pattern pNum = Pattern.compile("\"" + field + "\"\\s*:\\s*([0-9\\.]+)");
        Matcher mNum = pNum.matcher(json);
        if (mNum.find()) return mNum.group(1);

        return null;
    }

    private static int parseJsonIntField(String json, String field, int defaultVal) {
        String val = parseJsonField(json, field);
        if (val != null) {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private static long parseJsonLongField(String json, String field, long defaultVal) {
        String val = parseJsonField(json, field);
        if (val != null) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private static boolean parseJsonBooleanField(String json, String field, boolean defaultVal) {
        if (json == null || json.trim().isEmpty()) return defaultVal;
        Pattern pBool = Pattern.compile("\"" + field + "\"\\s*:\\s*\"?(true|false)\"?", Pattern.CASE_INSENSITIVE);
        Matcher m = pBool.matcher(json);
        if (m.find()) {
            return Boolean.parseBoolean(m.group(1));
        }
        return defaultVal;
    }

    private static String parseJsonObjectOrString(String json, String field) {
        if (json == null || json.trim().isEmpty()) return null;
        String val = parseJsonField(json, field);
        if (val != null) return val;
        Pattern pRaw = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\[.*?\\]|\\{.*?\\})", Pattern.DOTALL);
        Matcher m = pRaw.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static File resolveLevelSessionsDir() {
        File dir = new File("blocky_game/level_sessions");
        if (!dir.exists()) {
            File alt = new File("level_sessions");
            if (alt.exists()) {
                dir = alt;
            } else if (new File("blocky_game").isDirectory()) {
                dir = new File("blocky_game/level_sessions");
            } else {
                dir = new File("level_sessions");
            }
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir.getAbsoluteFile();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // --- REST Handlers ---

    private static class NewSessionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            SessionContext session = SessionManager.getInstance().getOrCreateSession(null);
            String json = "{\"status\":\"ok\",\"sessionId\":\"" + session.getSessionId() + "\"}";
            sendJson(exchange, 200, json);
        }
    }

    private static class SessionStateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);
            GameEngine engine = session.getEngine();
            Level level = engine.getCurrentLevel();

            int levelId = (level != null) ? level.getId() : 1;
            String xml = (level != null) ? engine.solutionToBlocklyXml(level) : "";
            int[][] grid = (level != null && level.getMap() != null) ? engine.buildGridForWebView(level.getMap()) : new int[0][0];

            Cell startCell = (level != null && level.getMap() != null) ? engine.getStartCell(level.getMap()) : null;
            Cell goalCell = (level != null && level.getMap() != null) ? engine.getGoalCell(level.getMap()) : null;
            Cell dmgCell = (level != null && level.getMap() != null) ? engine.getDmgCell(level.getMap()) : null;

            int startX = startCell != null ? startCell.getX() : 0;
            int startY = startCell != null ? startCell.getY() : 0;
            Cell targetGoal = dmgCell != null ? dmgCell : goalCell;
            int goalX = targetGoal != null ? targetGoal.getX() : 0;
            int goalY = targetGoal != null ? targetGoal.getY() : 0;

            StringBuilder gridSb = new StringBuilder("[");
            for (int r = 0; r < grid.length; r++) {
                if (r > 0) gridSb.append(",");
                gridSb.append("[");
                for (int c = 0; c < grid[r].length; c++) {
                    if (c > 0) gridSb.append(",");
                    gridSb.append(grid[r][c]);
                }
                gridSb.append("]");
            }
            gridSb.append("]");

            int[][] pathPts = new int[0][0];
            int startT = 0; // Default NORTH (0)
            if (level != null && level.getMap() != null) {
                Direction startDir = SimUtils.determineStartOrientation(level, startCell);
                startT = engine.directionToT(startDir);
                DebuggingService.DebugTraceResult traceRes = DebuggingService.computeTraceFromState(level, startX, startY, startDir);
                if (traceRes != null && traceRes.statePositions != null) {
                    pathPts = traceRes.statePositions;
                }
            }
            StringBuilder pathSb = new StringBuilder("[");
            for (int i = 0; i < pathPts.length; i++) {
                if (i > 0) pathSb.append(",");
                pathSb.append("[").append(pathPts[i][0]).append(",").append(pathPts[i][1]).append("]");
            }
            pathSb.append("]");

            StringBuilder sb = new StringBuilder("{");
            sb.append("\"status\":\"ok\",");
            sb.append("\"sessionId\":\"").append(session.getSessionId()).append("\",");
            sb.append("\"levelId\":").append(levelId).append(",");
            sb.append("\"xml\":\"").append(escapeJson(xml)).append("\",");
            sb.append("\"grid\":").append(gridSb.toString()).append(",");
            sb.append("\"startPos\":{\"x\":").append(startX).append(",\"y\":").append(startY).append("},");
            sb.append("\"startDirection\":").append(startT).append(",");
            sb.append("\"goalPos\":{\"x\":").append(goalX).append(",\"y\":").append(goalY).append("},");
            sb.append("\"maxBlocks\":").append(level != null ? level.getMaxBlocks() : -1).append(",");
            sb.append("\"momotRunning\":").append(session.isMomotRunning()).append(",");
            sb.append("\"momotStatus\":\"").append(escapeJson(session.getMomotStatus())).append("\",");
            sb.append("\"newPath\":").append(pathSb.toString()).append(",");
            sb.append("\"pastPath\":[]");
            sb.append("}");

            sendJson(exchange, 200, sb.toString());
        }
    }

    private static class SyncModelHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);

            String xml = parseJsonField(body, "xml");
            if (xml == null && !body.startsWith("{")) {
                xml = body; // fallback to raw XML body
            }
            long epoch = parseJsonLongField(body, "epoch", 0);
            boolean allowEmpty = parseJsonBooleanField(body, "allowEmpty", false);

            boolean applied = session.syncModel(epoch, xml, allowEmpty);
            sendJson(exchange, 200, "{\"status\":\"ok\",\"applied\":" + applied + "}");
        }
    }

    private static class SyncMapHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);

            long epoch = parseJsonLongField(body, "epoch", 0);
            String mapJson = parseJsonObjectOrString(body, "map");
            if (mapJson == null && body.trim().startsWith("[")) {
                mapJson = body;
            }

            boolean applied = session.syncMap(epoch, mapJson);
            sendJson(exchange, 200, "{\"status\":\"ok\",\"applied\":" + applied + "}");
        }
    }

    private static class SyncMetaHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);

            long epoch = parseJsonLongField(body, "epoch", 0);
            String metaJson = parseJsonObjectOrString(body, "meta");
            if (metaJson == null && body.trim().startsWith("{")) {
                metaJson = body;
            }

            boolean applied = session.syncLevelMeta(epoch, metaJson);
            sendJson(exchange, 200, "{\"status\":\"ok\",\"applied\":" + applied + "}");
        }
    }

    private static class SyncSnapshotHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);

            long epoch = parseJsonLongField(body, "epoch", 0);
            String mapJson = parseJsonObjectOrString(body, "map");
            String metaJson = parseJsonObjectOrString(body, "meta");
            String xml = parseJsonField(body, "xml");
            boolean allowEmpty = parseJsonBooleanField(body, "allowEmpty", false);

            boolean applied = session.applySnapshot(epoch, mapJson, metaJson, xml, allowEmpty);
            sendJson(exchange, 200, "{\"status\":\"ok\",\"applied\":" + applied + "}");
        }
    }

    private static class MomotRunHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);

            int seed = parseJsonIntField(body, "seed", 0);
            int pop = parseJsonIntField(body, "populationSize", 20);
            int eval = parseJsonIntField(body, "maxEvaluations", 1000);
            int runs = parseJsonIntField(body, "nrRuns", 1);
            int solLen = parseJsonIntField(body, "solutionLength", 10);

            long epoch = parseJsonLongField(body, "epoch", 0);
            String mapJson = parseJsonObjectOrString(body, "map");
            String metaJson = parseJsonObjectOrString(body, "meta");
            String xml = parseJsonField(body, "xml");

            boolean wasRunning = session.isMomotRunning();
            session.runMomotWithParams(epoch, mapJson, metaJson, xml, seed, pop, eval, runs, solLen);
            if (!wasRunning && session.isMomotRunning()) {
                String timerSid = extractTimerSessionId(exchange, body);
                int levelId = extractLevelId(exchange, body, session);
                recordActivity(timerSid, levelId, ActivityType.MOMOT_SEARCH);
            }

            String json = "{\"status\":\"ok\",\"running\":" + session.isMomotRunning() + ",\"outputDir\":\"" + escapeJson(session.getMomotCurrentOutputDir()) + "\"}";
            sendJson(exchange, 200, json);
        }
    }

    private static class MomotStopHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);

            session.stopMomotRun();
            sendJson(exchange, 200, "{\"status\":\"ok\",\"running\":false}");
        }
    }

    private static class MomotStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);

            StringBuilder sb = new StringBuilder("{");
            sb.append("\"running\":").append(session.isMomotRunning()).append(",");
            sb.append("\"status\":\"").append(escapeJson(session.getMomotStatus())).append("\",");
            sb.append("\"outputDir\":\"").append(escapeJson(session.getMomotCurrentOutputDir())).append("\",");
            sb.append("\"progress\":{");
            sb.append("\"run\":").append(session.getProgressRun()).append(",");
            sb.append("\"totalRuns\":").append(session.getProgressTotalRuns()).append(",");
            sb.append("\"gen\":").append(session.getProgressGen()).append(",");
            sb.append("\"totalGens\":").append(session.getProgressTotalGens()).append(",");
            sb.append("\"pct\":").append(String.format(java.util.Locale.US, "%.1f", session.getProgressPct()));
            sb.append("},");
            sb.append("\"logs\":[");
            List<String> logs = session.getMomotLogs();
            for (int i = 0; i < logs.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escapeJson(logs.get(i))).append("\"");
            }
            sb.append("]}");

            sendJson(exchange, 200, sb.toString());
        }
    }

    private static class MomotSolutionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);
            List<MomotResultsService.SolutionEntry> sols = session.listMomotSolutions();

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < sols.size(); i++) {
                MomotResultsService.SolutionEntry e = sols.get(i);
                if (i > 0) sb.append(",");
                sb.append("{");
                sb.append("\"outputDir\":\"").append(escapeJson(e.outputDir)).append("\",");
                sb.append("\"modelPath\":\"").append(escapeJson(e.modelPath)).append("\",");
                sb.append("\"objectiveLine\":\"").append(escapeJson(e.objectiveLine)).append("\",");
                sb.append("\"summary\":\"").append(escapeJson(e.summary)).append("\"");
                sb.append("}");
            }
            sb.append("]");

            sendJson(exchange, 200, sb.toString());
        }
    }

    private static class MomotSolutionPathHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);

            String modelPath = parseJsonField(body, "modelPath");
            if (modelPath == null || modelPath.trim().isEmpty()) {
                sendJson(exchange, 200, "{\"status\":\"ok\",\"path\":[]}");
                return;
            }

            try {
                File file = new File(modelPath.trim());
                if (!file.exists()) {
                    sendJson(exchange, 200, "{\"status\":\"ok\",\"path\":[]}");
                    return;
                }

                ResourceSet resSet = new ResourceSetImpl();
                URI uri = URI.createFileURI(file.getAbsolutePath());
                Resource res = resSet.createResource(uri);
                res.load(null);

                if (res.getContents().isEmpty()) {
                    sendJson(exchange, 200, "{\"status\":\"ok\",\"path\":[]}");
                    return;
                }

                Object root = res.getContents().get(0);
                Level level = null;
                if (root instanceof Game) {
                    Game game = (Game) root;
                    if (!game.getLevels().isEmpty()) level = game.getLevels().get(0);
                } else if (root instanceof Level) {
                    level = (Level) root;
                }

                if (level == null || level.getMap() == null) {
                    sendJson(exchange, 200, "{\"status\":\"ok\",\"path\":[]}");
                    return;
                }

                Cell startCell = session.getEngine().getStartCell(level.getMap());
                Direction startDir = SimUtils.determineStartOrientation(level, startCell);

                DebuggingService.DebugTraceResult result = DebuggingService.computeTraceFromState(
                    level,
                    startCell != null ? startCell.getX() : 0,
                    startCell != null ? startCell.getY() : 0,
                    startDir
                );

                int[][] pts = result.statePositions;
                StringBuilder sb = new StringBuilder("{\"status\":\"ok\",\"path\":[");
                for (int i = 0; i < pts.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append("[").append(pts[i][0]).append(",").append(pts[i][1]).append("]");
                }
                sb.append("]}");

                sendJson(exchange, 200, sb.toString());
            } catch (Exception e) {
                System.err.println("[MomotSolutionPathHandler] Error: " + e.getMessage());
                sendJson(exchange, 200, "{\"status\":\"ok\",\"path\":[]}");
            }
        }
    }

    private static class MomotLoadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);

            String modelPath = parseJsonField(body, "modelPath");
            boolean ok = session.loadMomotSolution(modelPath);

            GameEngine engine = session.getEngine();
            Level level = engine.getCurrentLevel();

            int levelId = (level != null) ? level.getId() : 1;
            String xml = (ok && level != null) ? engine.solutionToBlocklyXml(level) : "";

            if (ok) {
                String timerSid = extractTimerSessionId(exchange, body);
                int timerLevelId = extractLevelId(exchange, body, session);
                recordActivity(timerSid, timerLevelId, ActivityType.SOLUTION_LOAD);

                if (timerSid != null) {
                    String modelName = modelPath != null ? new File(modelPath).getName() : "solution.xmi";
                    String objectiveLine = "";
                    for (MomotResultsService.SolutionEntry e : session.listMomotSolutions()) {
                        if (modelPath != null && (modelPath.equals(e.modelPath) || (e.modelPath != null && modelName.equals(new File(e.modelPath).getName())))) {
                            if (e.objectiveLine != null) {
                                objectiveLine = e.objectiveLine;
                                break;
                            }
                        }
                    }
                    if (objectiveLine.isEmpty()) {
                        String fromBody = parseJsonField(body, "objectiveLine");
                        if (fromBody != null) objectiveLine = fromBody;
                    }
                    String timestamp = parseJsonField(body, "timestamp");
                    if (timestamp == null || timestamp.trim().isEmpty()) {
                        timestamp = java.time.Instant.now().toString();
                    }
                    String variant = parseJsonField(body, "variant");
                    if (variant == null || variant.trim().isEmpty()) {
                        variant = "momot";
                    }
                    appendSessionEvent(timerSid, "candidate_exploration", variant, timerLevelId, modelName, xml, modelPath, objectiveLine, timestamp);
                }
            }
            int[][] grid = (level != null && level.getMap() != null) ? engine.buildGridForWebView(level.getMap()) : new int[0][0];

            Cell startCell = (level != null && level.getMap() != null) ? engine.getStartCell(level.getMap()) : null;
            Cell goalCell = (level != null && level.getMap() != null) ? engine.getGoalCell(level.getMap()) : null;
            Cell dmgCell = (level != null && level.getMap() != null) ? engine.getDmgCell(level.getMap()) : null;

            int startX = startCell != null ? startCell.getX() : 0;
            int startY = startCell != null ? startCell.getY() : 0;
            Cell targetGoal = dmgCell != null ? dmgCell : goalCell;
            int goalX = targetGoal != null ? targetGoal.getX() : 0;
            int goalY = targetGoal != null ? targetGoal.getY() : 0;

            StringBuilder gridSb = new StringBuilder("[");
            for (int r = 0; r < grid.length; r++) {
                if (r > 0) gridSb.append(",");
                gridSb.append("[");
                for (int c = 0; c < grid[r].length; c++) {
                    if (c > 0) gridSb.append(",");
                    gridSb.append(grid[r][c]);
                }
                gridSb.append("]");
            }
            gridSb.append("]");

            int startT = 1;
            if (level != null && level.getMap() != null) {
                Direction startDir = SimUtils.determineStartOrientation(level, startCell);
                startT = engine.directionToT(startDir);
            }

            StringBuilder sb = new StringBuilder("{");
            sb.append("\"status\":\"").append(ok ? "ok" : "error").append("\",");
            sb.append("\"levelId\":").append(levelId).append(",");
            sb.append("\"xml\":\"").append(escapeJson(xml)).append("\",");
            sb.append("\"grid\":").append(gridSb.toString()).append(",");
            sb.append("\"startPos\":{\"x\":").append(startX).append(",\"y\":").append(startY).append("},");
            sb.append("\"goalPos\":{\"x\":").append(goalX).append(",\"y\":").append(goalY).append("},");
            sb.append("\"startDirection\":").append(startT);
            sb.append("}");

            sendJson(exchange, ok ? 200 : 400, sb.toString());
        }
    }

    private static class DebugStartHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);

            int q = parseJsonIntField(body, "q", 0);
            int s = parseJsonIntField(body, "s", 0);
            int t = parseJsonIntField(body, "t", 0);

            String resJson = session.getEngine().debugStart(q, s, t);
            sendJson(exchange, 200, resJson != null ? resJson : "{}");
        }
    }

    private static class DebugStepHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);

            String resJson = session.getEngine().debugStepOnce();
            sendJson(exchange, 200, resJson != null ? resJson : "{}");
        }
    }

    private static class DebugPauseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);

            String resJson = session.getEngine().debugTogglePause();
            sendJson(exchange, 200, resJson != null ? resJson : "{}");
        }
    }

    private static class DebugStopHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);

            String resJson = session.getEngine().debugStop();
            sendJson(exchange, 200, resJson != null ? resJson : "{}");
        }
    }

    private static class DebugSkipHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);

            String resJson = session.getEngine().debugSkipToEnd();
            sendJson(exchange, 200, resJson != null ? resJson : "{}");
        }
    }

    private static class DebugTickHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);

            String resJson = session.getEngine().debugTick();
            sendJson(exchange, 200, resJson != null ? resJson : "{}");
        }
    }

    private static class DirectManipulationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            String body = readRequestBody(exchange);
            SessionContext session = getSession(exchange, body);

            int q = parseJsonIntField(body, "q", 0);
            int s = parseJsonIntField(body, "s", 0);
            int t = parseJsonIntField(body, "t", 0);

            session.getEngine().teleportPegman(q, s, t);

            String timerSid = extractTimerSessionId(exchange, body);
            int levelId = extractLevelId(exchange, body, session);
            recordActivity(timerSid, levelId, ActivityType.DIRECT_MANIPULATION);

            sendJson(exchange, 200, "{\"status\":\"ok\"}");
        }
    }

    private static class SimulationRunHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendCorsAndNoContent(exchange);
                    return;
                }
                String body = readRequestBody(exchange);
                SessionContext session = getSession(exchange, body);

                String timerSid = extractTimerSessionId(exchange, body);
                int levelId = extractLevelId(exchange, body, session);
                boolean recordExecution = parseJsonBooleanField(body, "recordExecution", false);
                if (timerSid != null && recordExecution) {
                    recordActivity(timerSid, levelId, ActivityType.PROGRAM_RUN);
                    String variant = parseJsonField(body, "variant");
                    if (variant == null || variant.trim().isEmpty()) {
                        variant = "momot";
                    }
                    String timestamp = parseJsonField(body, "timestamp");
                    if (timestamp == null || timestamp.trim().isEmpty()) {
                        timestamp = java.time.Instant.now().toString();
                    }
                    String xml = parseJsonField(body, "xml");
                    if (xml == null || xml.trim().isEmpty()) {
                        Level curLvl = session.getEngine().getCurrentLevel();
                        if (curLvl != null) {
                            xml = session.getEngine().solutionToBlocklyXml(curLvl);
                        }
                    }
                    appendSessionEvent(timerSid, "program_run", variant, levelId, "workspace", xml, null, null, timestamp);
                }

                List<String> logs = session.getEngine().simulateUserProgramWithLogs();
                Level level = session.getEngine().getCurrentLevel();
                int[][] pathPts = new int[0][0];
                if (level != null && level.getMap() != null) {
                    Cell startCell = session.getEngine().getStartCell(level.getMap());
                    int startX = startCell != null ? startCell.getX() : 0;
                    int startY = startCell != null ? startCell.getY() : 0;
                    Direction startDir = SimUtils.determineStartOrientation(level, startCell);
                    DebuggingService.DebugTraceResult traceRes = DebuggingService.computeTraceFromState(level, startX, startY, startDir);
                    if (traceRes != null && traceRes.statePositions != null) {
                        pathPts = traceRes.statePositions;
                    }
                }
                StringBuilder pathSb = new StringBuilder("[");
                for (int i = 0; i < pathPts.length; i++) {
                    if (i > 0) pathSb.append(",");
                    pathSb.append("[").append(pathPts[i][0]).append(",").append(pathPts[i][1]).append("]");
                }
                pathSb.append("]");

                StringBuilder sb = new StringBuilder("{\"status\":\"ok\",");
                sb.append("\"levelId\":").append(level != null ? level.getId() : 1).append(",");
                sb.append("\"logs\":[");
                for (int i = 0; i < logs.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escapeJson(logs.get(i))).append("\"");
                }
                sb.append("],\"newPath\":").append(pathSb.toString()).append("}");
                sendJson(exchange, 200, sb.toString());
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(exchange, 500, "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    private static final Object SESSION_FILE_LOCK = new Object();

    enum ActivityType {
        PROGRAM_RUN,
        MOMOT_SEARCH,
        SOLUTION_LOAD,
        DIRECT_MANIPULATION
    }

    private static String extractTimerSessionId(HttpExchange exchange, String body) {
        if (exchange != null && exchange.getRequestHeaders() != null) {
            for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
                if ("x-timer-session-id".equalsIgnoreCase(entry.getKey())) {
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        String val = entry.getValue().get(0);
                        if (val != null && !val.trim().isEmpty()) {
                            return val.trim();
                        }
                    }
                }
            }
        }
        if (body != null && !body.isEmpty()) {
            String fromBody = parseJsonField(body, "timerSessionId");
            if (fromBody != null && !fromBody.trim().isEmpty()) {
                return fromBody.trim();
            }
        }
        return null;
    }

    private static int extractLevelId(HttpExchange exchange, String body, SessionContext session) {
        if (exchange != null && exchange.getRequestHeaders() != null) {
            for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
                if ("x-level-id".equalsIgnoreCase(entry.getKey())) {
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        try {
                            int lvl = Integer.parseInt(entry.getValue().get(0).trim());
                            if (lvl >= 1 && lvl <= 10) return lvl;
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        if (body != null && !body.isEmpty()) {
            int lvl = parseJsonIntField(body, "level", 0);
            if (lvl >= 1 && lvl <= 10) return lvl;
            lvl = parseJsonIntField(body, "levelId", 0);
            if (lvl >= 1 && lvl <= 10) return lvl;
        }
        if (session != null && session.getEngine() != null && session.getEngine().getCurrentLevel() != null) {
            int lvl = session.getEngine().getCurrentLevel().getId();
            if (lvl >= 1 && lvl <= 10) return lvl;
        }
        return 1;
    }

    private static void recordActivity(String timerSessionId, int levelId, ActivityType activityType) {
        if (timerSessionId == null || !timerSessionId.matches("^[a-zA-Z0-9_-]{1,128}$")) {
            return;
        }
        if (levelId < 1 || levelId > 10) {
            levelId = 1;
        }
        File dir = resolveLevelSessionsDir();
        File sessionFile = new File(dir, timerSessionId + ".json");

        synchronized (SESSION_FILE_LOCK) {
            if (!sessionFile.exists()) {
                try {
                    String initial = "{\n" +
                            "  \"sessionId\": \"" + escapeJson(timerSessionId) + "\",\n" +
                            "  \"userId\": \"anonymous\",\n" +
                            "  \"startedAt\": \"" + java.time.Instant.now().toString() + "\",\n" +
                            "  \"levels\": {}\n" +
                            "}";
                    Files.writeString(sessionFile.toPath(), initial, StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
            }
            if (!sessionFile.exists()) {
                return;
            }
            try {
                String content = new String(Files.readAllBytes(sessionFile.toPath()), StandardCharsets.UTF_8);
                String storedUserId = parseJsonField(content, "userId");
                if (storedUserId == null || storedUserId.trim().isEmpty()) {
                    storedUserId = "anonymous";
                }
                String startedAt = parseJsonField(content, "startedAt");
                if (startedAt == null || startedAt.trim().isEmpty()) {
                    startedAt = java.time.Instant.now().toString();
                }

                Map<Integer, LevelRecord> levels = new TreeMap<>();
                Pattern pLvl = Pattern.compile("\"(\\d+)\"\\s*:\\s*\\{([^}]+)\\}");
                Matcher mLvl = pLvl.matcher(content);
                while (mLvl.find()) {
                    try {
                        int l = Integer.parseInt(mLvl.group(1));
                        String inner = mLvl.group(2);
                        long eMs = parseJsonLongField(inner, "elapsedMs", 0L);
                        String v = parseJsonField(inner, "variant");
                        String u = parseJsonField(inner, "updatedAt");
                        int runs = parseJsonIntField(inner, "programRuns", 0);
                        int searches = parseJsonIntField(inner, "momotSearches", 0);
                        int loaded = parseJsonIntField(inner, "solutionsLoaded", 0);
                        int dms = parseJsonIntField(inner, "directManipulations", 0);
                        levels.put(l, new LevelRecord(eMs, v, u, runs, searches, loaded, dms));
                    } catch (Exception ignored) {}
                }

                LevelRecord rec = levels.get(levelId);
                if (rec == null) {
                    rec = new LevelRecord(0L, "momot", java.time.Instant.now().toString(), 0, 0, 0, 0);
                }

                int runs = rec.programRuns;
                int searches = rec.momotSearches;
                int loaded = rec.solutionsLoaded;
                int dms = rec.directManipulations;

                if (activityType == ActivityType.PROGRAM_RUN) runs++;
                else if (activityType == ActivityType.MOMOT_SEARCH) searches++;
                else if (activityType == ActivityType.SOLUTION_LOAD) loaded++;
                else if (activityType == ActivityType.DIRECT_MANIPULATION) dms++;

                levels.put(levelId, new LevelRecord(rec.elapsedMs, rec.variant, rec.updatedAt, runs, searches, loaded, dms));

                StringBuilder sb = new StringBuilder("{\n");
                sb.append("  \"sessionId\": \"").append(escapeJson(timerSessionId)).append("\",\n");
                sb.append("  \"userId\": \"").append(escapeJson(storedUserId)).append("\",\n");
                sb.append("  \"startedAt\": \"").append(escapeJson(startedAt)).append("\",\n");
                sb.append("  \"levels\": {");

                boolean first = true;
                for (Map.Entry<Integer, LevelRecord> entry : levels.entrySet()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\n    \"").append(entry.getKey()).append("\": {\n");
                    sb.append("      \"elapsedMs\": ").append(entry.getValue().elapsedMs).append(",\n");
                    sb.append("      \"variant\": \"").append(escapeJson(entry.getValue().variant)).append("\",\n");
                    sb.append("      \"updatedAt\": \"").append(escapeJson(entry.getValue().updatedAt)).append("\",\n");
                    sb.append("      \"programRuns\": ").append(entry.getValue().programRuns).append(",\n");
                    sb.append("      \"momotSearches\": ").append(entry.getValue().momotSearches).append(",\n");
                    sb.append("      \"solutionsLoaded\": ").append(entry.getValue().solutionsLoaded).append(",\n");
                    sb.append("      \"directManipulations\": ").append(entry.getValue().directManipulations).append("\n");
                    sb.append("    }");
                }
                if (!first) sb.append("\n  ");
                sb.append("}\n}");

                Files.write(sessionFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                System.err.println("[HttpSearchServer] Failed to record activity: " + e.getMessage());
            }
        }
    }

    private static void appendSessionEvent(String timerSessionId, String type, String variant, int levelId,
                                           String modelName, String xml, String modelPath, String objectiveLine,
                                           String timestamp) {
        if (timerSessionId == null || !timerSessionId.matches("^[a-zA-Z0-9_-]{1,128}$")) {
            return;
        }
        if (levelId < 1 || levelId > 10) {
            levelId = 1;
        }
        if (variant == null || variant.trim().isEmpty()) {
            variant = "momot";
        }
        if (timestamp == null || timestamp.trim().isEmpty()) {
            timestamp = java.time.Instant.now().toString();
        }
        File baseDir = resolveLevelSessionsDir();
        File sessionDir = new File(baseDir, timerSessionId);
        File modelsDir = new File(sessionDir, "models");

        synchronized (SESSION_FILE_LOCK) {
            try {
                if (!modelsDir.exists()) {
                    modelsDir.mkdirs();
                }
                File eventsFile = new File(sessionDir, "events.jsonl");
                int eventIndex = 1;
                if (eventsFile.exists()) {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(eventsFile), StandardCharsets.UTF_8))) {
                        while (r.readLine() != null) {
                            eventIndex++;
                        }
                    }
                }
                String eventId = String.valueOf(eventIndex);
                String modelFile = "models/" + eventId + ".xml";

                // 1. Write XML file
                File xmlFile = new File(modelsDir, eventId + ".xml");
                Files.writeString(xmlFile.toPath(), xml != null ? xml : "", StandardCharsets.UTF_8);

                // 2. If candidate, copy XMI file
                if (modelPath != null && !modelPath.trim().isEmpty()) {
                    File srcXmi = new File(modelPath.trim());
                    if (!srcXmi.exists()) {
                        File alt = new File("blocky_game", modelPath.trim());
                        if (alt.exists()) srcXmi = alt;
                    }
                    if (srcXmi.exists() && srcXmi.isFile()) {
                        File dstXmi = new File(modelsDir, eventId + ".xmi");
                        Files.copy(srcXmi.toPath(), dstXmi.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }

                // 3. Append to events.jsonl
                StringBuilder jsonLine = new StringBuilder();
                jsonLine.append("{\"timestamp\":\"").append(escapeJson(timestamp)).append("\",");
                jsonLine.append("\"type\":\"").append(escapeJson(type)).append("\",");
                jsonLine.append("\"variant\":\"").append(escapeJson(variant)).append("\",");
                jsonLine.append("\"level\":").append(levelId).append(",");
                jsonLine.append("\"modelName\":\"").append(escapeJson(modelName != null ? modelName : "")).append("\",");
                jsonLine.append("\"modelFile\":\"").append(escapeJson(modelFile)).append("\"");
                if (modelPath != null) {
                    jsonLine.append(",\"modelPath\":\"").append(escapeJson(modelPath)).append("\"");
                }
                if (objectiveLine != null) {
                    jsonLine.append(",\"objectiveLine\":\"").append(escapeJson(objectiveLine)).append("\"");
                }
                jsonLine.append("}\n");

                try (FileWriter fw = new FileWriter(eventsFile, StandardCharsets.UTF_8, true)) {
                    fw.write(jsonLine.toString());
                }
            } catch (Exception e) {
                System.err.println("[HttpSearchServer] Failed to append session event: " + e.getMessage());
            }
        }
    }

    private static class LevelRecord {
        long elapsedMs;
        String variant;
        String updatedAt;
        int programRuns;
        int momotSearches;
        int solutionsLoaded;
        int directManipulations;

        LevelRecord(long elapsedMs, String variant, String updatedAt) {
            this(elapsedMs, variant, updatedAt, 0, 0, 0, 0);
        }

        LevelRecord(long elapsedMs, String variant, String updatedAt, int programRuns, int momotSearches, int solutionsLoaded, int directManipulations) {
            this.elapsedMs = elapsedMs;
            this.variant = variant != null ? variant : "momot";
            this.updatedAt = updatedAt != null ? updatedAt : java.time.Instant.now().toString();
            this.programRuns = Math.max(0, programRuns);
            this.momotSearches = Math.max(0, momotSearches);
            this.solutionsLoaded = Math.max(0, solutionsLoaded);
            this.directManipulations = Math.max(0, directManipulations);
        }
    }

    private static class LevelTimeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }

            try {
                String body = readRequestBody(exchange);
                String sessionId = parseJsonField(body, "sessionId");
                if (sessionId == null || sessionId.trim().isEmpty()) {
                    if (exchange.getRequestHeaders() != null) {
                        List<String> hdr = exchange.getRequestHeaders().get("X-Timer-Session-ID");
                        if (hdr != null && !hdr.isEmpty()) sessionId = hdr.get(0);
                        if (sessionId == null || sessionId.trim().isEmpty()) {
                            List<String> hdr2 = exchange.getRequestHeaders().get("X-Session-ID");
                            if (hdr2 != null && !hdr2.isEmpty()) sessionId = hdr2.get(0);
                        }
                    }
                    if (sessionId == null || sessionId.trim().isEmpty()) {
                        String query = exchange.getRequestURI().getQuery();
                        if (query != null && query.contains("sessionId=")) {
                            for (String param : query.split("&")) {
                                if (param.startsWith("sessionId=")) {
                                    sessionId = param.substring("sessionId=".length());
                                    break;
                                }
                            }
                        }
                    }
                }

                if (sessionId == null || !sessionId.matches("^[a-zA-Z0-9_-]{1,128}$")) {
                    sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid or missing sessionId\"}");
                    return;
                }

                String userId = parseJsonField(body, "userId");
                String variant = parseJsonField(body, "variant");
                int level = parseJsonIntField(body, "level", 0);
                long elapsedMs = parseJsonLongField(body, "elapsedMs", -1L);
                String timestamp = parseJsonField(body, "timestamp");
                if (timestamp == null || timestamp.trim().isEmpty()) {
                    timestamp = java.time.Instant.now().toString();
                }

                File dir = resolveLevelSessionsDir();
                File sessionFile = new File(dir, sessionId + ".json");

                Map<Integer, LevelRecord> levels = new TreeMap<>();
                String startedAt = timestamp;
                String storedUserId = userId != null && !userId.trim().isEmpty() ? userId : "anonymous";

                synchronized (SESSION_FILE_LOCK) {
                    if (sessionFile.exists()) {
                        String existingContent = new String(Files.readAllBytes(sessionFile.toPath()), StandardCharsets.UTF_8);
                        String sUid = parseJsonField(existingContent, "userId");
                        if (sUid != null && !sUid.trim().isEmpty()) {
                            storedUserId = sUid;
                        }
                        String sStart = parseJsonField(existingContent, "startedAt");
                        if (sStart != null && !sStart.trim().isEmpty()) {
                            startedAt = sStart;
                        }

                        Pattern pLvl = Pattern.compile("\"(\\d+)\"\\s*:\\s*\\{([^}]+)\\}");
                        Matcher mLvl = pLvl.matcher(existingContent);
                        while (mLvl.find()) {
                            try {
                                int l = Integer.parseInt(mLvl.group(1));
                                String inner = mLvl.group(2);
                                long eMs = parseJsonLongField(inner, "elapsedMs", 0L);
                                String v = parseJsonField(inner, "variant");
                                String u = parseJsonField(inner, "updatedAt");
                                int runs = parseJsonIntField(inner, "programRuns", 0);
                                int searches = parseJsonIntField(inner, "momotSearches", 0);
                                int loaded = parseJsonIntField(inner, "solutionsLoaded", 0);
                                int dms = parseJsonIntField(inner, "directManipulations", 0);
                                levels.put(l, new LevelRecord(eMs, v, u, runs, searches, loaded, dms));
                            } catch (Exception ignored) {}
                        }
                    }

                    if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && level >= 1 && level <= 10 && elapsedMs >= 0) {
                        LevelRecord existing = levels.get(level);
                        if (existing == null) {
                            levels.put(level, new LevelRecord(elapsedMs, variant, timestamp, 0, 0, 0, 0));
                        } else {
                            long maxElapsed = Math.max(existing.elapsedMs, elapsedMs);
                            String useVariant = variant != null ? variant : existing.variant;
                            String useUpdatedAt = (elapsedMs >= existing.elapsedMs) ? timestamp : existing.updatedAt;
                            levels.put(level, new LevelRecord(maxElapsed, useVariant, useUpdatedAt, existing.programRuns, existing.momotSearches, existing.solutionsLoaded, existing.directManipulations));
                        }
                    }

                    StringBuilder sb = new StringBuilder("{\n");
                    sb.append("  \"sessionId\": \"").append(escapeJson(sessionId)).append("\",\n");
                    sb.append("  \"userId\": \"").append(escapeJson(storedUserId)).append("\",\n");
                    sb.append("  \"startedAt\": \"").append(escapeJson(startedAt)).append("\",\n");
                    sb.append("  \"levels\": {");

                    boolean first = true;
                    for (Map.Entry<Integer, LevelRecord> entry : levels.entrySet()) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("\n    \"").append(entry.getKey()).append("\": {\n");
                        sb.append("      \"elapsedMs\": ").append(entry.getValue().elapsedMs).append(",\n");
                        sb.append("      \"variant\": \"").append(escapeJson(entry.getValue().variant)).append("\",\n");
                        sb.append("      \"updatedAt\": \"").append(escapeJson(entry.getValue().updatedAt)).append("\",\n");
                        sb.append("      \"programRuns\": ").append(entry.getValue().programRuns).append(",\n");
                        sb.append("      \"momotSearches\": ").append(entry.getValue().momotSearches).append(",\n");
                        sb.append("      \"solutionsLoaded\": ").append(entry.getValue().solutionsLoaded).append(",\n");
                        sb.append("      \"directManipulations\": ").append(entry.getValue().directManipulations).append("\n");
                        sb.append("    }");
                    }
                    if (!first) sb.append("\n  ");
                    sb.append("}\n}");

                    Files.write(sessionFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));

                    StringBuilder resp = new StringBuilder("{\"status\":\"ok\",");
                    resp.append("\"sessionId\":\"").append(escapeJson(sessionId)).append("\",");
                    resp.append("\"userId\":\"").append(escapeJson(storedUserId)).append("\",");
                    resp.append("\"levels\":{");
                    first = true;
                    for (Map.Entry<Integer, LevelRecord> entry : levels.entrySet()) {
                        if (!first) resp.append(",");
                        first = false;
                        resp.append("\"").append(entry.getKey()).append("\":{");
                        resp.append("\"elapsedMs\":").append(entry.getValue().elapsedMs).append(",");
                        resp.append("\"variant\":\"").append(escapeJson(entry.getValue().variant)).append("\",");
                        resp.append("\"updatedAt\":\"").append(escapeJson(entry.getValue().updatedAt)).append("\",");
                        resp.append("\"programRuns\":").append(entry.getValue().programRuns).append(",");
                        resp.append("\"momotSearches\":").append(entry.getValue().momotSearches).append(",");
                        resp.append("\"solutionsLoaded\":").append(entry.getValue().solutionsLoaded).append(",");
                        resp.append("\"directManipulations\":").append(entry.getValue().directManipulations);
                        resp.append("}");
                    }
                    resp.append("}}");

                    sendJson(exchange, 200, resp.toString());
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(exchange, 500, "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    // --- Admin API Handlers & Support ---

    private static final Set<String> ACTIVE_ADMIN_TOKENS = Collections.synchronizedSet(new HashSet<>());

    /** Admin password from blocky.adminPassword, otherwise BLOCKY_ADMIN_PASSWORD. Never hardcoded. */
    private static String adminPassword() {
        String prop = System.getProperty("blocky.adminPassword");
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String env = System.getenv("BLOCKY_ADMIN_PASSWORD");
        return env == null ? "" : env;
    }

    private static boolean adminPasswordMatches(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String expected = adminPassword();
        return !expected.isEmpty() && expected.equals(candidate);
    }

    private static boolean isAuthorizedAdmin(HttpExchange exchange, String requestBody) {
        if (exchange.getRequestHeaders() != null) {
            List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
            if (authHeaders != null) {
                for (String h : authHeaders) {
                    if (h.startsWith("Bearer ")) {
                        String token = h.substring("Bearer ".length()).trim();
                        if (ACTIVE_ADMIN_TOKENS.contains(token) || adminPasswordMatches(token)) return true;
                    }
                }
            }
            List<String> tokenHeaders = exchange.getRequestHeaders().get("X-Admin-Token");
            if (tokenHeaders != null && !tokenHeaders.isEmpty()) {
                String token = tokenHeaders.get(0).trim();
                if (ACTIVE_ADMIN_TOKENS.contains(token) || adminPasswordMatches(token)) return true;
            }
            List<String> passHeaders = exchange.getRequestHeaders().get("X-Admin-Password");
            if (passHeaders != null && !passHeaders.isEmpty()) {
                String pass = passHeaders.get(0).trim();
                if (adminPasswordMatches(pass)) return true;
            }
        }
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String p : query.split("&")) {
                if (p.startsWith("token=")) {
                    String token = p.substring("token=".length()).trim();
                    if (ACTIVE_ADMIN_TOKENS.contains(token) || adminPasswordMatches(token)) return true;
                }
                if (p.startsWith("password=")) {
                    String pass = p.substring("password=".length()).trim();
                    if (adminPasswordMatches(pass)) return true;
                }
            }
        }
        if (requestBody != null) {
            String token = parseJsonField(requestBody, "token");
            if (token != null && (ACTIVE_ADMIN_TOKENS.contains(token) || adminPasswordMatches(token))) return true;
            String pass = parseJsonField(requestBody, "password");
            if (adminPasswordMatches(pass)) return true;
        }
        return false;
    }

    private static class SessionEvent {
        String timestamp;
        String type;
        String variant;
        int level;
        String modelName;
        String modelFile;
        String modelPath;
        String objectiveLine;
        String xml = "";
    }

    private static class AdminSessionSummary {
        String sessionId;
        String userId;
        String startedAt;
        long totalElapsedMs;
        int completedLevels;
        int totalProgramRuns;
        int totalMomotSearches;
        int totalSolutionsLoaded;
        int totalDirectManipulations;
        Map<Integer, LevelRecord> levels = new TreeMap<>();
        List<SessionEvent> events = new ArrayList<>();
    }

    private static List<SessionEvent> loadSessionEvents(File dir, String sessionId) {
        List<SessionEvent> events = new ArrayList<>();
        if (dir == null || sessionId == null) return events;
        File sessionDir = new File(dir, sessionId);
        File eventsFile = new File(sessionDir, "events.jsonl");
        if (!eventsFile.exists() || !eventsFile.isFile()) {
            return events;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(eventsFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                SessionEvent evt = new SessionEvent();
                evt.timestamp = parseJsonField(line, "timestamp");
                evt.type = parseJsonField(line, "type");
                evt.variant = parseJsonField(line, "variant");
                evt.level = parseJsonIntField(line, "level", 1);
                evt.modelName = parseJsonField(line, "modelName");
                evt.modelFile = parseJsonField(line, "modelFile");
                evt.modelPath = parseJsonField(line, "modelPath");
                evt.objectiveLine = parseJsonField(line, "objectiveLine");

                if (evt.modelFile != null && !evt.modelFile.trim().isEmpty()) {
                    File xmlFile = new File(sessionDir, evt.modelFile.trim());
                    if (xmlFile.exists() && xmlFile.isFile()) {
                        try {
                            evt.xml = Files.readString(xmlFile.toPath(), StandardCharsets.UTF_8);
                        } catch (Exception ignored) {}
                    }
                }
                if (evt.xml == null) evt.xml = "";
                events.add(evt);
            }
        } catch (Exception e) {
            System.err.println("[HttpSearchServer] Failed to load session events for " + sessionId + ": " + e.getMessage());
        }
        return events;
    }

    private static List<AdminSessionSummary> loadAllSessionSummaries() {
        List<AdminSessionSummary> list = new ArrayList<>();
        File dir = resolveLevelSessionsDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                try {
                    String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    AdminSessionSummary sum = new AdminSessionSummary();
                    sum.sessionId = parseJsonField(content, "sessionId");
                    if (sum.sessionId == null || sum.sessionId.trim().isEmpty()) {
                        sum.sessionId = f.getName().replace(".json", "");
                    }
                    sum.userId = parseJsonField(content, "userId");
                    if (sum.userId == null || sum.userId.trim().isEmpty()) {
                        sum.userId = "anonymous";
                    }
                    sum.startedAt = parseJsonField(content, "startedAt");
                    if (sum.startedAt == null || sum.startedAt.trim().isEmpty()) {
                        sum.startedAt = java.time.Instant.ofEpochMilli(f.lastModified()).toString();
                    }

                    Pattern pLvl = Pattern.compile("\"(\\d+)\"\\s*:\\s*\\{([^}]+)\\}");
                    Matcher mLvl = pLvl.matcher(content);
                    long totalMs = 0;
                    int count = 0;
                    int totalRuns = 0;
                    int totalSearches = 0;
                    int totalLoaded = 0;
                    int totalDms = 0;
                    while (mLvl.find()) {
                        try {
                            int l = Integer.parseInt(mLvl.group(1));
                            String inner = mLvl.group(2);
                            long eMs = parseJsonLongField(inner, "elapsedMs", 0L);
                            String v = parseJsonField(inner, "variant");
                            String u = parseJsonField(inner, "updatedAt");
                            int runs = parseJsonIntField(inner, "programRuns", 0);
                            int searches = parseJsonIntField(inner, "momotSearches", 0);
                            int loaded = parseJsonIntField(inner, "solutionsLoaded", 0);
                            int dms = parseJsonIntField(inner, "directManipulations", 0);
                            sum.levels.put(l, new LevelRecord(eMs, v, u, runs, searches, loaded, dms));
                            totalMs += eMs;
                            if (eMs > 0) count++;
                            totalRuns += runs;
                            totalSearches += searches;
                            totalLoaded += loaded;
                            totalDms += dms;
                        } catch (Exception ignored) {}
                    }
                    sum.totalElapsedMs = totalMs;
                    sum.completedLevels = count;
                    sum.totalProgramRuns = totalRuns;
                    sum.totalMomotSearches = totalSearches;
                    sum.totalSolutionsLoaded = totalLoaded;
                    sum.totalDirectManipulations = totalDms;
                    sum.events = loadSessionEvents(dir, sum.sessionId);
                    list.add(sum);
                } catch (Exception ignored) {}
            }
        }
        list.sort((a, b) -> (b.startedAt != null ? b.startedAt : "").compareTo(a.startedAt != null ? a.startedAt : ""));
        return list;
    }

    private static String formatTimeSecOrMs(long ms) {
        if (ms <= 0) return "-";
        long totalSec = ms / 1000;
        long mins = totalSec / 60;
        long secs = totalSec % 60;
        long hrs = mins / 60;
        mins = mins % 60;
        if (hrs > 0) {
            return String.format("%02d:%02d:%02d", hrs, mins, secs);
        }
        return String.format("%02d:%02d", mins, secs);
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    private static String getBlockPreviewScript() {
        Path p = Paths.get("blocky_game/src/blocky_game/blockly-games-web/common/blockPreview.js");
        if (Files.exists(p)) {
            try {
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        Path alt = Paths.get("src/blocky_game/blockly-games-web/common/blockPreview.js");
        if (Files.exists(alt)) {
            try {
                return new String(Files.readAllBytes(alt), StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        return "/* blockPreview.js not found */";
    }

    private static String generateAdminHtmlReport(List<AdminSessionSummary> sessions) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n");
        sb.append("<title>Blockly Maze - All User Sessions Report</title>\n");
        sb.append("<style>\n");
        sb.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif; background: #f6f8fa; color: #24292f; margin: 0; padding: 24px; }\n");
        sb.append(".header { background: #ffffff; padding: 20px 24px; border-radius: 8px; border: 1px solid #d0d7de; margin-bottom: 24px; }\n");
        sb.append("h1 { margin: 0 0 8px 0; font-size: 22px; color: #0969da; }\n");
        sb.append(".stats { display: flex; gap: 16px; font-size: 13px; color: #57606a; margin-top: 10px; flex-wrap: wrap; }\n");
        sb.append(".stat-item { background: #f1f8ff; padding: 6px 12px; border-radius: 6px; border: 1px solid #c8e1ff; }\n");
        sb.append("table { width: 100%; border-collapse: collapse; background: #ffffff; border-radius: 8px; overflow: hidden; border: 1px solid #d0d7de; box-shadow: 0 1px 3px rgba(0,0,0,0.08); font-size: 12px; }\n");
        sb.append("th, td { padding: 8px 10px; border-bottom: 1px solid #e1e4e8; text-align: left; }\n");
        sb.append("th { background: #f6f8fa; font-weight: 600; color: #24292f; border-bottom: 2px solid #d0d7de; }\n");
        sb.append("tr:hover { background: #fdfdfd; }\n");
        sb.append(".badge { display: inline-block; padding: 2px 6px; border-radius: 4px; font-size: 11px; font-weight: 600; background: #ddf4ff; color: #0969da; }\n");
        sb.append(".badge-run { display: inline-block; padding: 2px 6px; border-radius: 4px; font-size: 11px; font-weight: 600; background: #ddf4ff; color: #0969da; }\n");
        sb.append(".badge-cand { display: inline-block; padding: 2px 6px; border-radius: 4px; font-size: 11px; font-weight: 600; background: #dafbe1; color: #1a7f37; }\n");
        sb.append(".total-cell { font-weight: 700; color: #1a7f37; }\n");
        sb.append(".session-events-section { margin-top: 24px; background: #ffffff; padding: 16px; border-radius: 8px; border: 1px solid #d0d7de; }\n");
        sb.append(".event-card { background: #f6f8fa; border: 1px solid #d0d7de; border-radius: 6px; padding: 12px; margin-top: 10px; }\n");
        sb.append(".event-meta { font-size: 12px; color: #57606a; margin-bottom: 8px; display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }\n");
        sb.append(".xml-toggle { margin-top: 8px; font-size: 11px; color: #57606a; }\n");
        sb.append(".xml-toggle pre { background: #ffffff; padding: 8px; border: 1px solid #d0d7de; border-radius: 4px; overflow-x: auto; max-height: 200px; font-size: 11px; }\n");
        sb.append("</style>\n</head>\n<body>\n");

        long grandTotalMs = 0;
        int grandTotalRuns = 0;
        int grandTotalSearches = 0;
        int grandTotalLoaded = 0;
        int grandTotalDms = 0;
        Set<String> uniqueUsers = new HashSet<>();
        for (AdminSessionSummary s : sessions) {
            grandTotalMs += s.totalElapsedMs;
            grandTotalRuns += s.totalProgramRuns;
            grandTotalSearches += s.totalMomotSearches;
            grandTotalLoaded += s.totalSolutionsLoaded;
            grandTotalDms += s.totalDirectManipulations;
            uniqueUsers.add(s.userId);
        }

        sb.append("<div class=\"header\">\n");
        sb.append("  <h1>Blockly Maze - Sessions & Timing Report</h1>\n");
        sb.append("  <div class=\"stats\">\n");
        sb.append("    <div class=\"stat-item\"><strong>Total Sessions:</strong> ").append(sessions.size()).append("</div>\n");
        sb.append("    <div class=\"stat-item\"><strong>Unique Users:</strong> ").append(uniqueUsers.size()).append("</div>\n");
        sb.append("    <div class=\"stat-item\"><strong>Total Playtime:</strong> ").append(formatTimeSecOrMs(grandTotalMs)).append("</div>\n");
        sb.append("    <div class=\"stat-item\"><strong>Program Runs:</strong> ").append(grandTotalRuns).append("</div>\n");
        sb.append("    <div class=\"stat-item\"><strong>MoMoT Searches:</strong> ").append(grandTotalSearches).append("</div>\n");
        sb.append("    <div class=\"stat-item\"><strong>Solutions Loaded:</strong> ").append(grandTotalLoaded).append("</div>\n");
        sb.append("    <div class=\"stat-item\"><strong>Direct Manipulations:</strong> ").append(grandTotalDms).append("</div>\n");
        sb.append("    <div class=\"stat-item\"><strong>Generated At:</strong> ").append(java.time.Instant.now().toString()).append("</div>\n");
        sb.append("  </div>\n");
        sb.append("</div>\n");

        sb.append("<table>\n<thead>\n<tr>\n");
        sb.append("  <th>Session ID</th>\n  <th>User ID</th>\n  <th>Started At</th>\n  <th>Runs</th>\n  <th>Searches</th>\n  <th>Loaded</th>\n  <th>DMs</th>\n  <th>Lvl 1</th>\n  <th>Lvl 2</th>\n  <th>Lvl 3</th>\n  <th>Lvl 4</th>\n  <th>Lvl 5</th>\n  <th>Lvl 6</th>\n  <th>Lvl 7</th>\n  <th>Lvl 8</th>\n  <th>Lvl 9</th>\n  <th>Lvl 10</th>\n  <th>Total Duration</th>\n");
        sb.append("</tr>\n</thead>\n<tbody>\n");

        if (sessions.isEmpty()) {
            sb.append("<tr><td colspan=\"18\" style=\"text-align:center; padding: 20px;\">No sessions recorded yet.</td></tr>\n");
        } else {
            for (AdminSessionSummary s : sessions) {
                sb.append("<tr>\n");
                sb.append("  <td><span class=\"badge\">").append(escapeHtml(s.sessionId)).append("</span></td>\n");
                sb.append("  <td><strong>").append(escapeHtml(s.userId)).append("</strong></td>\n");
                sb.append("  <td>").append(escapeHtml(s.startedAt != null ? s.startedAt : "-")).append("</td>\n");
                sb.append("  <td><strong>").append(s.totalProgramRuns).append("</strong></td>\n");
                sb.append("  <td><strong>").append(s.totalMomotSearches).append("</strong></td>\n");
                sb.append("  <td><strong>").append(s.totalSolutionsLoaded).append("</strong></td>\n");
                sb.append("  <td><strong>").append(s.totalDirectManipulations).append("</strong></td>\n");
                for (int l = 1; l <= 10; l++) {
                    LevelRecord rec = s.levels.get(l);
                    if (rec != null && (rec.elapsedMs > 0 || rec.programRuns > 0 || rec.momotSearches > 0 || rec.solutionsLoaded > 0 || rec.directManipulations > 0)) {
                        sb.append("  <td>");
                        if (rec.elapsedMs > 0) {
                            sb.append(formatTimeSecOrMs(rec.elapsedMs)).append("<br><small style=\"color:#8c959f;\">").append(escapeHtml(rec.variant)).append("</small>");
                        } else {
                            sb.append("-");
                        }
                        if (rec.programRuns > 0 || rec.momotSearches > 0 || rec.solutionsLoaded > 0 || rec.directManipulations > 0) {
                            sb.append("<br><small style=\"color:#57606a;\">R:").append(rec.programRuns)
                              .append(" S:").append(rec.momotSearches)
                              .append(" L:").append(rec.solutionsLoaded)
                              .append(" D:").append(rec.directManipulations)
                              .append("</small>");
                        }
                        sb.append("</td>\n");
                    } else {
                        sb.append("  <td style=\"color:#8c959f;\">-</td>\n");
                    }
                }
                sb.append("  <td class=\"total-cell\">").append(formatTimeSecOrMs(s.totalElapsedMs)).append("</td>\n");
                sb.append("</tr>\n");
            }
        }
        sb.append("</tbody>\n</table>\n");

        sb.append("<h2 style=\"margin-top: 36px; font-size: 18px; color: #0969da;\">Session Event Timelines & Workspace Snapshots</h2>\n");
        boolean anyEvents = false;
        for (AdminSessionSummary s : sessions) {
            if (s.events == null || s.events.isEmpty()) continue;
            anyEvents = true;
            sb.append("<div class=\"session-events-section\">\n");
            sb.append("  <h3 style=\"margin: 0 0 10px 0; font-size: 15px;\">Session: <span class=\"badge\">")
              .append(escapeHtml(s.sessionId)).append("</span> &bull; User: <strong>")
              .append(escapeHtml(s.userId)).append("</strong> (").append(s.events.size()).append(" events)</h3>\n");

            for (SessionEvent ev : s.events) {
                sb.append("  <div class=\"event-card\">\n");
                sb.append("    <div class=\"event-meta\">\n");
                String badgeClass = "program_run".equals(ev.type) ? "badge-run" : "badge-cand";
                sb.append("      <span class=\"").append(badgeClass).append("\">").append(escapeHtml(ev.type)).append("</span>\n");
                sb.append("      <span><strong>Level:</strong> ").append(ev.level).append("</span>\n");
                sb.append("      <span><strong>Variant:</strong> ").append(escapeHtml(ev.variant != null ? ev.variant : "-")).append("</span>\n");
                sb.append("      <span><strong>Time:</strong> ").append(escapeHtml(ev.timestamp != null ? ev.timestamp : "-")).append("</span>\n");
                if (ev.modelName != null && !ev.modelName.isEmpty()) {
                    sb.append("      <span><strong>Model:</strong> ").append(escapeHtml(ev.modelName)).append("</span>\n");
                }
                if (ev.objectiveLine != null && !ev.objectiveLine.isEmpty()) {
                    sb.append("      <span><strong>Objective:</strong> ").append(escapeHtml(ev.objectiveLine)).append("</span>\n");
                }
                sb.append("    </div>\n");

                if (ev.xml != null && !ev.xml.trim().isEmpty()) {
                    sb.append("    <div class=\"block-preview\" data-xml=\"").append(escapeHtml(ev.xml)).append("\"></div>\n");
                    sb.append("    <details class=\"xml-toggle\"><summary>Raw Workspace XML</summary><pre>").append(escapeHtml(ev.xml)).append("</pre></details>\n");
                } else {
                    sb.append("    <div style=\"font-size:12px; color:#8c959f; font-style:italic;\">No workspace XML snapshot recorded for this event.</div>\n");
                }
                sb.append("  </div>\n");
            }
            sb.append("</div>\n");
        }
        if (!anyEvents) {
            sb.append("<p style=\"color:#57606a; font-size:13px;\">No session events recorded yet.</p>\n");
        }

        sb.append("<script>\n").append(getBlockPreviewScript()).append("\n</script>\n");
        sb.append("<script>\n");
        sb.append("document.addEventListener('DOMContentLoaded', function() {\n");
        sb.append("    if (window.BlockPreview && typeof window.BlockPreview.renderAll === 'function') {\n");
        sb.append("        window.BlockPreview.renderAll(document);\n");
        sb.append("    }\n");
        sb.append("});\n");
        sb.append("</script>\n");
        sb.append("</body>\n</html>");
        return sb.toString();
    }

    private static class AdminLoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"status\":\"error\",\"message\":\"Method Not Allowed\"}");
                return;
            }
            try {
                String body = readRequestBody(exchange);
                String password = parseJsonField(body, "password");
                if (adminPasswordMatches(password)) {
                    String token = "admin_" + UUID.randomUUID().toString().replace("-", "");
                    ACTIVE_ADMIN_TOKENS.add(token);
                    sendJson(exchange, 200, "{\"status\":\"ok\",\"token\":\"" + token + "\"}");
                } else {
                    sendJson(exchange, 401, "{\"status\":\"error\",\"message\":\"Invalid admin password\"}");
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(exchange, 500, "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    private static class AdminSessionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            try {
                if (!isAuthorizedAdmin(exchange, null)) {
                    sendJson(exchange, 401, "{\"status\":\"error\",\"message\":\"Unauthorized\"}");
                    return;
                }

                String q = null;
                String query = exchange.getRequestURI().getQuery();
                if (query != null) {
                    for (String p : query.split("&")) {
                        if (p.startsWith("q=")) {
                            q = URLDecoder.decode(p.substring("q=".length()), StandardCharsets.UTF_8).toLowerCase().trim();
                        }
                    }
                }

                List<AdminSessionSummary> all = loadAllSessionSummaries();
                if (q != null && !q.isEmpty()) {
                    final String filter = q;
                    all.removeIf(s -> !s.sessionId.toLowerCase().contains(filter) && !s.userId.toLowerCase().contains(filter));
                }

                StringBuilder sb = new StringBuilder("{\"status\":\"ok\",\"count\":").append(all.size()).append(",\"sessions\":[");
                for (int i = 0; i < all.size(); i++) {
                    if (i > 0) sb.append(",");
                    AdminSessionSummary s = all.get(i);
                    sb.append("{")
                      .append("\"sessionId\":\"").append(escapeJson(s.sessionId)).append("\",")
                      .append("\"userId\":\"").append(escapeJson(s.userId)).append("\",")
                      .append("\"startedAt\":\"").append(escapeJson(s.startedAt)).append("\",")
                      .append("\"totalElapsedMs\":").append(s.totalElapsedMs).append(",")
                      .append("\"completedLevels\":").append(s.completedLevels).append(",")
                      .append("\"totalProgramRuns\":").append(s.totalProgramRuns).append(",")
                      .append("\"totalMomotSearches\":").append(s.totalMomotSearches).append(",")
                      .append("\"totalSolutionsLoaded\":").append(s.totalSolutionsLoaded).append(",")
                      .append("\"totalDirectManipulations\":").append(s.totalDirectManipulations).append(",")
                      .append("\"levels\":{");
                    boolean firstLvl = true;
                    for (Map.Entry<Integer, LevelRecord> entry : s.levels.entrySet()) {
                        if (!firstLvl) sb.append(",");
                        firstLvl = false;
                        sb.append("\"").append(entry.getKey()).append("\":{")
                          .append("\"elapsedMs\":").append(entry.getValue().elapsedMs).append(",")
                          .append("\"variant\":\"").append(escapeJson(entry.getValue().variant)).append("\",")
                          .append("\"updatedAt\":\"").append(escapeJson(entry.getValue().updatedAt)).append("\",")
                          .append("\"programRuns\":").append(entry.getValue().programRuns).append(",")
                          .append("\"momotSearches\":").append(entry.getValue().momotSearches).append(",")
                          .append("\"solutionsLoaded\":").append(entry.getValue().solutionsLoaded).append(",")
                          .append("\"directManipulations\":").append(entry.getValue().directManipulations)
                          .append("}");
                    }
                    sb.append("},\"events\":[");
                    for (int evIdx = 0; evIdx < s.events.size(); evIdx++) {
                        if (evIdx > 0) sb.append(",");
                        SessionEvent ev = s.events.get(evIdx);
                        sb.append("{")
                          .append("\"timestamp\":\"").append(escapeJson(ev.timestamp != null ? ev.timestamp : "")).append("\",")
                          .append("\"type\":\"").append(escapeJson(ev.type != null ? ev.type : "")).append("\",")
                          .append("\"variant\":\"").append(escapeJson(ev.variant != null ? ev.variant : "")).append("\",")
                          .append("\"level\":").append(ev.level).append(",")
                          .append("\"modelName\":\"").append(escapeJson(ev.modelName != null ? ev.modelName : "")).append("\",")
                          .append("\"modelFile\":\"").append(escapeJson(ev.modelFile != null ? ev.modelFile : "")).append("\",")
                          .append("\"modelPath\":\"").append(escapeJson(ev.modelPath != null ? ev.modelPath : "")).append("\",")
                          .append("\"objectiveLine\":\"").append(escapeJson(ev.objectiveLine != null ? ev.objectiveLine : "")).append("\",")
                          .append("\"xml\":").append(ev.xml != null ? "\"" + escapeJson(ev.xml) + "\"" : "\"\"")
                          .append("}");
                    }
                    sb.append("]}");
                }
                sb.append("]}");
                sendJson(exchange, 200, sb.toString());
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(exchange, 500, "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    private static class AdminExportHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }
            try {
                if (!isAuthorizedAdmin(exchange, null)) {
                    sendJson(exchange, 401, "{\"status\":\"error\",\"message\":\"Unauthorized\"}");
                    return;
                }

                String format = "json";
                String query = exchange.getRequestURI().getQuery();
                if (query != null) {
                    for (String p : query.split("&")) {
                        if (p.startsWith("format=")) {
                            format = p.substring("format=".length()).trim().toLowerCase();
                        }
                    }
                }

                List<AdminSessionSummary> all = loadAllSessionSummaries();

                if ("html".equalsIgnoreCase(format)) {
                    String html = generateAdminHtmlReport(all);
                    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                    exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"all_sessions_report.html\"");
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } else {
                    StringBuilder sb = new StringBuilder("[\n");
                    for (int i = 0; i < all.size(); i++) {
                        if (i > 0) sb.append(",\n");
                        AdminSessionSummary s = all.get(i);
                        sb.append("  {\n")
                          .append("    \"sessionId\": \"").append(escapeJson(s.sessionId)).append("\",\n")
                          .append("    \"userId\": \"").append(escapeJson(s.userId)).append("\",\n")
                          .append("    \"startedAt\": \"").append(escapeJson(s.startedAt)).append("\",\n")
                          .append("    \"totalElapsedMs\": ").append(s.totalElapsedMs).append(",\n")
                          .append("    \"completedLevels\": ").append(s.completedLevels).append(",\n")
                          .append("    \"totalProgramRuns\": ").append(s.totalProgramRuns).append(",\n")
                          .append("    \"totalMomotSearches\": ").append(s.totalMomotSearches).append(",\n")
                          .append("    \"totalSolutionsLoaded\": ").append(s.totalSolutionsLoaded).append(",\n")
                          .append("    \"totalDirectManipulations\": ").append(s.totalDirectManipulations).append(",\n")
                          .append("    \"levels\": {");
                        boolean firstLvl = true;
                        for (Map.Entry<Integer, LevelRecord> entry : s.levels.entrySet()) {
                            if (!firstLvl) sb.append(",");
                            firstLvl = false;
                            sb.append("\n      \"").append(entry.getKey()).append("\": {\n")
                              .append("        \"elapsedMs\": ").append(entry.getValue().elapsedMs).append(",\n")
                              .append("        \"variant\": \"").append(escapeJson(entry.getValue().variant)).append("\",\n")
                              .append("        \"updatedAt\": \"").append(escapeJson(entry.getValue().updatedAt)).append("\",\n")
                              .append("        \"programRuns\": ").append(entry.getValue().programRuns).append(",\n")
                              .append("        \"momotSearches\": ").append(entry.getValue().momotSearches).append(",\n")
                              .append("        \"solutionsLoaded\": ").append(entry.getValue().solutionsLoaded).append(",\n")
                              .append("        \"directManipulations\": ").append(entry.getValue().directManipulations).append("\n")
                              .append("      }");
                        }
                        if (!firstLvl) sb.append("\n    ");
                        sb.append("},\n    \"events\": [");
                        for (int evIdx = 0; evIdx < s.events.size(); evIdx++) {
                            if (evIdx > 0) sb.append(",");
                            SessionEvent ev = s.events.get(evIdx);
                            sb.append("\n      {\n")
                              .append("        \"timestamp\": \"").append(escapeJson(ev.timestamp != null ? ev.timestamp : "")).append("\",\n")
                              .append("        \"type\": \"").append(escapeJson(ev.type != null ? ev.type : "")).append("\",\n")
                              .append("        \"variant\": \"").append(escapeJson(ev.variant != null ? ev.variant : "")).append("\",\n")
                              .append("        \"level\": ").append(ev.level).append(",\n")
                              .append("        \"modelName\": \"").append(escapeJson(ev.modelName != null ? ev.modelName : "")).append("\",\n")
                              .append("        \"modelFile\": \"").append(escapeJson(ev.modelFile != null ? ev.modelFile : "")).append("\",\n")
                              .append("        \"modelPath\": \"").append(escapeJson(ev.modelPath != null ? ev.modelPath : "")).append("\",\n")
                              .append("        \"objectiveLine\": \"").append(escapeJson(ev.objectiveLine != null ? ev.objectiveLine : "")).append("\",\n")
                              .append("        \"xml\": ").append(ev.xml != null ? "\"" + escapeJson(ev.xml) + "\"" : "\"\"").append("\n")
                              .append("      }");
                        }
                        if (!s.events.isEmpty()) sb.append("\n    ");
                        sb.append("]\n  }");
                    }
                    sb.append("\n]");
                    byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                    exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"all_sessions.json\"");
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(exchange, 500, "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    // --- Static Web Asset Server ---

    private static class StaticFileHandler implements HttpHandler {
        private final File baseWebDir;

        public StaticFileHandler() {
            File webDir = new File("blocky_game/src/blocky_game/blockly-games-web");
            if (!webDir.exists()) {
                webDir = new File("src/blocky_game/blockly-games-web");
            }
            if (!webDir.exists()) {
                webDir = new File("blockly-games-web");
            }
            File resolved = webDir.getAbsoluteFile();
            try {
                resolved = webDir.getCanonicalFile();
            } catch (IOException ignored) {}
            this.baseWebDir = resolved;
            System.out.println("[HttpSearchServer] Serving static web assets from: " + baseWebDir.getAbsolutePath());
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCorsAndNoContent(exchange);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (path == null || path.equals("/") || path.trim().isEmpty()) {
                path = "/maze.html";
            }

            // Remove leading slash for relative file lookup
            if (path.startsWith("/")) {
                path = path.substring(1);
            }

            File requestedFile = new File(baseWebDir, path).getCanonicalFile();
            if (!requestedFile.exists() && !path.contains(".")) {
                File htmlFallback = new File(baseWebDir, path + ".html").getCanonicalFile();
                if (htmlFallback.exists() && htmlFallback.isFile()) {
                    requestedFile = htmlFallback;
                }
            }
            String reqPath = requestedFile.getPath();
            String basePath = baseWebDir.getPath();
            boolean isUnderBase = reqPath.equalsIgnoreCase(basePath) || reqPath.toLowerCase().startsWith(basePath.toLowerCase() + File.separator) || reqPath.toLowerCase().startsWith(basePath.toLowerCase());
            if (!isUnderBase || !requestedFile.exists() || requestedFile.isDirectory()) {
                String response = "404 Not Found";
                exchange.sendResponseHeaders(404, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }

            String contentType = getContentType(requestedFile.getName());
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

            byte[] fileBytes = Files.readAllBytes(requestedFile.toPath());
            exchange.sendResponseHeaders(200, fileBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(fileBytes);
            }
        }

        private String getContentType(String filename) {
            String lower = filename.toLowerCase();
            if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
            if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (lower.endsWith(".css")) return "text/css; charset=utf-8";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".svg")) return "image/svg+xml";
            if (lower.endsWith(".json")) return "application/json";
            if (lower.endsWith(".gif")) return "image/gif";
            return "application/octet-stream";
        }
    }
}
