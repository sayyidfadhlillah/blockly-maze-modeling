package blocky_game;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

class HttpSearchServerTest {

    private static HttpServer server;
    private static int port;
    private static String baseUrl;
    private static HttpClient client;

    private static final String TEST_ADMIN_PASSWORD = "test-admin";

    @BeforeAll
    static void setUp() throws IOException {
        System.setProperty("blocky.adminPassword", TEST_ADMIN_PASSWORD);
        server = HttpSearchServer.startServer(0);
        port = server.getAddress().getPort();
        baseUrl = "http://localhost:" + port;
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testNewSessionAndState() throws Exception {
        HttpRequest reqNew = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/session/new"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> respNew = client.send(reqNew, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respNew.statusCode());
        String bodyNew = respNew.body();
        assertTrue(bodyNew.contains("sessionId"));

        // Extract session ID
        String sid = bodyNew.replaceAll(".*\"sessionId\":\"([^\"]+)\".*", "$1");
        assertNotNull(sid);
        assertFalse(sid.isEmpty());

        HttpRequest reqState = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/session/state"))
                .header("X-Session-ID", sid)
                .GET()
                .build();

        HttpResponse<String> respState = client.send(reqState, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respState.statusCode());
        String bodyState = respState.body();
        assertTrue(bodyState.contains("\"status\":\"ok\""));
        assertTrue(bodyState.contains("\"grid\":"));
    }

    @Test
    void testSyncModelAndSimulationRun() throws Exception {
        HttpRequest reqNew = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/session/new"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> respNew = client.send(reqNew, HttpResponse.BodyHandlers.ofString());
        String sid = respNew.body().replaceAll(".*\"sessionId\":\"([^\"]+)\".*", "$1");

        String sampleXml = "<xml><block type=\"maze_moveForward\"></block></xml>";
        HttpRequest reqSync = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/session/sync"))
                .header("Content-Type", "application/json")
                .header("X-Session-ID", sid)
                .POST(HttpRequest.BodyPublishers.ofString("{\"xml\":\"" + sampleXml.replace("\"", "\\\"") + "\"}"))
                .build();

        HttpResponse<String> respSync = client.send(reqSync, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respSync.statusCode());

        HttpRequest reqSim = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/simulation/run"))
                .header("Content-Type", "application/json")
                .header("X-Session-ID", sid)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> respSim = client.send(reqSim, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respSim.statusCode());
        assertTrue(respSim.body().contains("\"status\":\"ok\""));
    }

    @Test
    void testDirectManipulationAndMomotEndpoints() throws Exception {
        HttpRequest reqNew = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/session/new"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> respNew = client.send(reqNew, HttpResponse.BodyHandlers.ofString());
        String sid = respNew.body().replaceAll(".*\"sessionId\":\"([^\"]+)\".*", "$1");

        // Test Direct Manipulation request
        HttpRequest reqDm = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/dm/request"))
                .header("Content-Type", "application/json")
                .header("X-Session-ID", sid)
                .POST(HttpRequest.BodyPublishers.ofString("{\"q\":1,\"s\":0,\"t\":1}"))
                .build();

        HttpResponse<String> respDm = client.send(reqDm, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respDm.statusCode());

        // Test MoMoT Status
        HttpRequest reqStatus = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/momot/status"))
                .header("X-Session-ID", sid)
                .GET()
                .build();

        HttpResponse<String> respStatus = client.send(reqStatus, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respStatus.statusCode());
        assertTrue(respStatus.body().contains("\"running\":"));

        // Test MoMoT Solutions
        HttpRequest reqSol = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/momot/solutions"))
                .header("X-Session-ID", sid)
                .GET()
                .build();

        HttpResponse<String> respSol = client.send(reqSol, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respSol.statusCode());

        // Test MoMoT Stop
        HttpRequest reqStop = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/momot/stop"))
                .header("X-Session-ID", sid)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> respStop = client.send(reqStop, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respStop.statusCode());
    }

    @Test
    void testStaticFileServingAndOriginalMaze() throws Exception {
        // 1. Momot augmented maze.html
        HttpRequest reqMomot = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/maze.html"))
                .GET()
                .build();
        HttpResponse<String> respMomot = client.send(reqMomot, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respMomot.statusCode());
        assertTrue(respMomot.body().contains("webBridge.js"));
        assertTrue(respMomot.body().contains("blockyUIOverlay.js"));

        // 2. Root path defaults to maze.html
        HttpRequest reqRoot = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/"))
                .GET()
                .build();
        HttpResponse<String> respRoot = client.send(reqRoot, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respRoot.statusCode());
        assertTrue(respRoot.body().contains("webBridge.js"));

        // 3. Original maze.html entry point
        HttpRequest reqOrig = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/maze-original.html"))
                .GET()
                .build();
        HttpResponse<String> respOrig = client.send(reqOrig, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respOrig.statusCode());
        assertTrue(respOrig.body().contains("common/boot.js"));
        assertFalse(respOrig.body().contains("webBridge.js"));
        assertFalse(respOrig.body().contains("blockyUIOverlay.js"));

        // 4. Classic maze.html entry point
        HttpRequest reqClassic = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/maze-classic.html"))
                .GET()
                .build();
        HttpResponse<String> respClassic = client.send(reqClassic, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respClassic.statusCode());
        assertTrue(respClassic.body().contains("common/boot.js"));
        assertFalse(respClassic.body().contains("webBridge.js"));

        // 5. Extensionless /maze-original clean URL
        HttpRequest reqOrigClean = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/maze-original"))
                .GET()
                .build();
        HttpResponse<String> respOrigClean = client.send(reqOrigClean, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respOrigClean.statusCode());
        assertTrue(respOrigClean.body().contains("common/boot.js"));
        assertFalse(respOrigClean.body().contains("webBridge.js"));

        // 6. Extensionless /maze-classic clean URL
        HttpRequest reqClassicClean = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/maze-classic"))
                .GET()
                .build();
        HttpResponse<String> respClassicClean = client.send(reqClassicClean, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respClassicClean.statusCode());
        assertTrue(respClassicClean.body().contains("common/boot.js"));

        // 7. Admin page /admin.html and /admin
        HttpRequest reqAdmin = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin.html"))
                .GET()
                .build();
        HttpResponse<String> respAdmin = client.send(reqAdmin, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respAdmin.statusCode());
        assertTrue(respAdmin.body().contains("Admin Authentication"));

        HttpRequest reqAdminClean = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin"))
                .GET()
                .build();
        HttpResponse<String> respAdminClean = client.send(reqAdminClean, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respAdminClean.statusCode());
        assertTrue(respAdminClean.body().contains("Admin Authentication"));
    }

    @Test
    void testLevelTimeRecording() throws Exception {
        String testSessionId = "junit_user_" + System.currentTimeMillis();
        String json1 = "{\"sessionId\":\"" + testSessionId + "\",\"userId\":\"junit_user\",\"variant\":\"original\",\"level\":1,\"elapsedMs\":25000}";

        // 1. Initial report for level 1
        HttpRequest req1 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/level-time"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json1))
                .build();
        HttpResponse<String> resp1 = client.send(req1, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp1.statusCode());
        String body1 = resp1.body();
        assertTrue(body1.contains("\"status\":\"ok\""));
        assertTrue(body1.contains(testSessionId));
        assertTrue(body1.contains("\"1\":{\"elapsedMs\":25000"));

        // 2. Report higher elapsed time for level 1
        String json2 = "{\"sessionId\":\"" + testSessionId + "\",\"userId\":\"junit_user\",\"variant\":\"original\",\"level\":1,\"elapsedMs\":45000}";
        HttpRequest req2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/level-time"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json2))
                .build();
        HttpResponse<String> resp2 = client.send(req2, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp2.statusCode());
        assertTrue(resp2.body().contains("\"1\":{\"elapsedMs\":45000"));

        // 3. Report lower elapsed time for level 1 (should NOT decrease existing max elapsed)
        String json3 = "{\"sessionId\":\"" + testSessionId + "\",\"userId\":\"junit_user\",\"variant\":\"original\",\"level\":1,\"elapsedMs\":10000}";
        HttpRequest req3 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/level-time"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json3))
                .build();
        HttpResponse<String> resp3 = client.send(req3, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp3.statusCode());
        assertTrue(resp3.body().contains("\"1\":{\"elapsedMs\":45000"));

        // 4. Report time for level 2
        String json4 = "{\"sessionId\":\"" + testSessionId + "\",\"userId\":\"junit_user\",\"variant\":\"momot\",\"level\":2,\"elapsedMs\":30000}";
        HttpRequest req4 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/level-time"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json4))
                .build();
        HttpResponse<String> resp4 = client.send(req4, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp4.statusCode());
        assertTrue(resp4.body().contains("\"1\":{\"elapsedMs\":45000"));
        assertTrue(resp4.body().contains("\"2\":{\"elapsedMs\":30000"));

        // 5. Invalid session ID path traversal attempt rejected
        String badJson = "{\"sessionId\":\"../evil_path\",\"userId\":\"bad\",\"level\":1,\"elapsedMs\":1000}";
        HttpRequest reqBad = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/level-time"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(badJson))
                .build();
        HttpResponse<String> respBad = client.send(reqBad, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, respBad.statusCode());
    }

    @Test
    void testAdminPanelEndpoints() throws Exception {
        // 1. Invalid login attempt
        HttpRequest badLoginReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"wrong_password\"}"))
                .build();
        HttpResponse<String> badLoginResp = client.send(badLoginReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, badLoginResp.statusCode());

        HttpRequest goodLoginReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"" + TEST_ADMIN_PASSWORD + "\"}"))
                .build();
        HttpResponse<String> goodLoginResp = client.send(goodLoginReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, goodLoginResp.statusCode());
        String loginBody = goodLoginResp.body();
        assertTrue(loginBody.contains("\"status\":\"ok\""));
        assertTrue(loginBody.contains("\"token\":"));

        String token = loginBody.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        assertNotNull(token);
        assertFalse(token.isEmpty());

        // 3. Unauthorized access to /api/admin/sessions without token
        HttpRequest unauthReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/sessions"))
                .GET()
                .build();
        HttpResponse<String> unauthResp = client.send(unauthReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthResp.statusCode());

        // 4. Authorized access to /api/admin/sessions with Bearer token
        HttpRequest authReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/sessions"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> authResp = client.send(authReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, authResp.statusCode());
        String sessionsBody = authResp.body();
        assertTrue(sessionsBody.contains("\"status\":\"ok\""));
        assertTrue(sessionsBody.contains("\"sessions\":"));

        // 5. Query filtering
        HttpRequest queryReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/sessions?q=junit_user"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> queryResp = client.send(queryReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, queryResp.statusCode());
        assertTrue(queryResp.body().contains("junit_user"));

        // 6. Export as JSON
        HttpRequest exportJsonReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/export?format=json&token=" + token))
                .GET()
                .build();
        HttpResponse<String> exportJsonResp = client.send(exportJsonReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, exportJsonResp.statusCode());
        assertTrue(exportJsonResp.headers().firstValue("Content-Type").orElse("").contains("json"));
        assertTrue(exportJsonResp.body().startsWith("["));

        // 7. Export as HTML
        HttpRequest exportHtmlReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/export?format=html&token=" + token))
                .GET()
                .build();
        HttpResponse<String> exportHtmlResp = client.send(exportHtmlReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, exportHtmlResp.statusCode());
        assertTrue(exportHtmlResp.headers().firstValue("Content-Type").orElse("").contains("text/html"));
        assertTrue(exportHtmlResp.body().contains("<!DOCTYPE html>"));
        assertTrue(exportHtmlResp.body().contains("Blockly Maze"));
    }

    @Test
    void testActivityTrackingAndPreservation() throws Exception {
        String testActSid = "junit_act_" + System.currentTimeMillis();

        // 1. Initial level time creation for level 1
        String initTimeJson = "{\"sessionId\":\"" + testActSid + "\",\"userId\":\"act_user\",\"variant\":\"momot\",\"level\":1,\"elapsedMs\":10000}";
        HttpRequest reqInit = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/level-time"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(initTimeJson))
                .build();
        HttpResponse<String> respInit = client.send(reqInit, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respInit.statusCode());
        String bodyInit = respInit.body();
        assertTrue(bodyInit.contains("\"programRuns\":0"));
        assertTrue(bodyInit.contains("\"directManipulations\":0"));

        // 2a. Background simulation run WITHOUT recordExecution - must NOT increment runs
        HttpRequest reqSimBg = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/simulation/run"))
                .header("Content-Type", "application/json")
                .header("X-Timer-Session-ID", testActSid)
                .header("X-Level-ID", "1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"timerSessionId\":\"" + testActSid + "\",\"level\":1}"))
                .build();
        HttpResponse<String> respSimBg = client.send(reqSimBg, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respSimBg.statusCode());

        // 2b. Explicit user program run 1 with recordExecution: true
        HttpRequest reqSim1 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/simulation/run"))
                .header("Content-Type", "application/json")
                .header("X-Timer-Session-ID", testActSid)
                .header("X-Level-ID", "1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"timerSessionId\":\"" + testActSid + "\",\"level\":1,\"recordExecution\":true,\"xml\":\"<xml><block type=\\\"maze_moveForward\\\"></block></xml>\"}"))
                .build();
        HttpResponse<String> respSim1 = client.send(reqSim1, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respSim1.statusCode());

        // 3. Explicit user program run 2 with recordExecution: true
        HttpRequest reqSim2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/simulation/run"))
                .header("Content-Type", "application/json")
                .header("X-Timer-Session-ID", testActSid)
                .header("X-Level-ID", "1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"timerSessionId\":\"" + testActSid + "\",\"level\":1,\"recordExecution\":true,\"xml\":\"<xml><block type=\\\"maze_turn\\\"></block></xml>\"}"))
                .build();
        HttpResponse<String> respSim2 = client.send(reqSim2, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respSim2.statusCode());

        // 4. Direct Manipulation request on level 1
        HttpRequest reqDm = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/dm/request"))
                .header("Content-Type", "application/json")
                .header("X-Timer-Session-ID", testActSid)
                .header("X-Level-ID", "1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"q\":1,\"s\":0,\"t\":1,\"timerSessionId\":\"" + testActSid + "\",\"level\":1}"))
                .build();
        HttpResponse<String> respDm = client.send(reqDm, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respDm.statusCode());

        // 5. Query /api/level-time to verify activity counts recorded
        HttpRequest reqCheck = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/level-time?sessionId=" + testActSid))
                .GET()
                .build();
        HttpResponse<String> respCheck = client.send(reqCheck, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respCheck.statusCode());
        String bodyCheck = respCheck.body();
        assertTrue(bodyCheck.contains("\"programRuns\":2"));
        assertTrue(bodyCheck.contains("\"directManipulations\":1"));
        assertTrue(bodyCheck.contains("\"momotSearches\":0"));
        assertTrue(bodyCheck.contains("\"solutionsLoaded\":0"));

        // 6. Update elapsed time for level 1 and ensure activity counts are NOT wiped out
        String updateTimeJson = "{\"sessionId\":\"" + testActSid + "\",\"userId\":\"act_user\",\"variant\":\"momot\",\"level\":1,\"elapsedMs\":22000}";
        HttpRequest reqUpdate = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/level-time"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(updateTimeJson))
                .build();
        HttpResponse<String> respUpdate = client.send(reqUpdate, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respUpdate.statusCode());
        String bodyUpdate = respUpdate.body();
        assertTrue(bodyUpdate.contains("\"elapsedMs\":22000"));
        assertTrue(bodyUpdate.contains("\"programRuns\":2"));
        assertTrue(bodyUpdate.contains("\"directManipulations\":1"));

        // 7. Verify Admin API shows activity counts and events
        HttpRequest loginReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"" + TEST_ADMIN_PASSWORD + "\"}"))
                .build();
        HttpResponse<String> loginResp = client.send(loginReq, HttpResponse.BodyHandlers.ofString());
        String token = loginResp.body().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        HttpRequest adminReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/sessions?q=" + testActSid))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> adminResp = client.send(adminReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, adminResp.statusCode());
        String adminBody = adminResp.body();
        assertTrue(adminBody.contains("\"totalProgramRuns\":2"));
        assertTrue(adminBody.contains("\"totalDirectManipulations\":1"));
        assertTrue(adminBody.contains("\"programRuns\":2"));
        assertTrue(adminBody.contains("\"events\":"));
        assertTrue(adminBody.contains("\"type\":\"program_run\""));
    }

    @Test
    void testSnapshotAndEpochHandling() throws Exception {
        HttpRequest reqNew = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/session/new"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> respNew = client.send(reqNew, HttpResponse.BodyHandlers.ofString());
        String sid = respNew.body().replaceAll(".*\"sessionId\":\"([^\"]+)\".*", "$1");

        // 1. Snapshot with epoch 1 and 1 block
        String snap1 = "{"
                + "\"epoch\":1,"
                + "\"map\":\"[[0,0,0],[2,1,3],[0,0,0]]\","
                + "\"meta\":\"{\\\"level\\\":1,\\\"maxBlocks\\\":10,\\\"startDirection\\\":1}\","
                + "\"xml\":\"<xml><block type=\\\"maze_moveForward\\\"></block></xml>\","
                + "\"allowEmpty\":false"
                + "}";
        HttpRequest reqSnap1 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/session/snapshot"))
                .header("Content-Type", "application/json")
                .header("X-Session-ID", sid)
                .POST(HttpRequest.BodyPublishers.ofString(snap1))
                .build();
        HttpResponse<String> respSnap1 = client.send(reqSnap1, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respSnap1.statusCode());
        assertTrue(respSnap1.body().contains("\"applied\":true"));

        // 2. Snapshot with epoch 2 (next level, allowed empty)
        String snap2 = "{"
                + "\"epoch\":2,"
                + "\"map\":\"[[0,0,0],[2,1,3],[0,0,0]]\","
                + "\"meta\":\"{\\\"level\\\":2,\\\"maxBlocks\\\":10,\\\"startDirection\\\":1}\","
                + "\"xml\":\"<xml></xml>\","
                + "\"allowEmpty\":true"
                + "}";
        HttpRequest reqSnap2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/session/snapshot"))
                .header("Content-Type", "application/json")
                .header("X-Session-ID", sid)
                .POST(HttpRequest.BodyPublishers.ofString(snap2))
                .build();
        HttpResponse<String> respSnap2 = client.send(reqSnap2, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respSnap2.statusCode());
        assertTrue(respSnap2.body().contains("\"applied\":true"));

        // 3. Stale snapshot from epoch 1 arrives late -> rejected
        HttpRequest reqSnapStale = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/session/snapshot"))
                .header("Content-Type", "application/json")
                .header("X-Session-ID", sid)
                .POST(HttpRequest.BodyPublishers.ofString(snap1))
                .build();
        HttpResponse<String> respSnapStale = client.send(reqSnapStale, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respSnapStale.statusCode());
        assertTrue(respSnapStale.body().contains("\"applied\":false"));

        // 4. Verify session state reflects epoch 2 and level 2
        HttpRequest reqState = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/session/state"))
                .header("X-Session-ID", sid)
                .GET()
                .build();
        HttpResponse<String> respState = client.send(reqState, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respState.statusCode());
        assertTrue(respState.body().contains("\"levelId\":2"));
    }

    @Test
    void testEventsLoggingAndExport() throws Exception {
        String evSid = "test-ev-sess-" + System.currentTimeMillis();
        String initJson = "{\"sessionId\":\"" + evSid + "\",\"userId\":\"event_tester\",\"variant\":\"momot\",\"level\":1,\"elapsedMs\":5000}";
        HttpRequest reqInit = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/level-time"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(initJson))
                .build();
        HttpResponse<String> respInit = client.send(reqInit, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respInit.statusCode());

        // 1. Send Run Program with recordExecution: true and XML snapshot
        String sampleXml = "<xml><block type=\"maze_moveForward\"><next><block type=\"maze_turn\"><field name=\"DIR\">turnRight</field></block></next></block></xml>";
        HttpRequest reqRun = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/simulation/run"))
                .header("Content-Type", "application/json")
                .header("X-Timer-Session-ID", evSid)
                .header("X-Level-ID", "1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"timerSessionId\":\"" + evSid + "\",\"level\":1,\"variant\":\"momot\",\"recordExecution\":true,\"xml\":\"" + sampleXml.replace("\"", "\\\"") + "\"}"))
                .build();
        HttpResponse<String> respRun = client.send(reqRun, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respRun.statusCode());

        // 2. Check Admin Login and fetch sessions JSON
        HttpRequest loginReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"" + TEST_ADMIN_PASSWORD + "\"}"))
                .build();
        HttpResponse<String> loginResp = client.send(loginReq, HttpResponse.BodyHandlers.ofString());
        String token = loginResp.body().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        HttpRequest adminReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/sessions?q=" + evSid))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> adminResp = client.send(adminReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, adminResp.statusCode());
        String adminBody = adminResp.body();
        assertTrue(adminBody.contains("\"events\":"));
        assertTrue(adminBody.contains("\"type\":\"program_run\""));
        assertTrue(adminBody.contains("maze_moveForward"));

        // 3. Test HTML Export
        HttpRequest exportHtmlReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/export?format=html&token=" + token))
                .GET()
                .build();
        HttpResponse<String> exportHtmlResp = client.send(exportHtmlReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, exportHtmlResp.statusCode());
        assertTrue(exportHtmlResp.headers().firstValue("Content-Type").orElse("").contains("text/html"));
        String htmlBody = exportHtmlResp.body();
        assertTrue(htmlBody.contains("BlockPreview"));
        assertTrue(htmlBody.contains("block-preview"));
        assertTrue(htmlBody.contains("maze_moveForward"));

        // 4. Test JSON Export
        HttpRequest exportJsonReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/export?format=json&token=" + token))
                .GET()
                .build();
        HttpResponse<String> exportJsonResp = client.send(exportJsonReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, exportJsonResp.statusCode());
        assertTrue(exportJsonResp.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        String jsonBody = exportJsonResp.body();
        assertTrue(jsonBody.contains("\"events\":"));
        assertTrue(jsonBody.contains("\"type\": \"program_run\""));
    }
}
