const { describe, it, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const { JSDOM } = require('jsdom');
const path = require('node:path');
const fs = require('node:fs');

describe('webBridge.js Test Suite', () => {
    let dom;
    let window;

    beforeEach(() => {
        dom = new JSDOM('<!DOCTYPE html><html><body><div id="blockly"></div><svg id="svgMaze"></svg></body></html>', {
            url: 'http://localhost/',
            runScripts: 'dangerously',
            resources: 'usable'
        });
        window = dom.window;
        global.window = window;
        global.document = window.document;
        global.localStorage = window.localStorage;

        // Mock fetch
        global.fetch = async (url, options = {}) => {
            if (url.includes('/api/session/new')) {
                return {
                    json: async () => ({ sessionId: 'test-session-123' })
                };
            }
            if (url.includes('/api/session/state')) {
                return {
                    json: async () => ({
                        status: 'ok',
                        grid: [[2, 1, 3]],
                        startPos: { x: 0, y: 0 },
                        levelId: 1,
                        maxBlocks: 10,
                        newPath: [[0, 0], [1, 0]],
                        pastPath: []
                    })
                };
            }
            if (url.includes('/api/session/sync') || url.includes('/api/simulation/run')) {
                return {
                    json: async () => ({
                        status: 'ok',
                        newPath: [[0, 0], [1, 0], [2, 0]],
                        logs: ['Step 1: MoveForward']
                    })
                };
            }
            if (url.includes('/api/momot/solutions')) {
                return {
                    json: async () => ([
                        { modelPath: 'solution1.xmi', objectiveLine: '-1 3 2 0' }
                    ])
                };
            }
            if (url.includes('/api/momot/load')) {
                return {
                    json: async () => ({ status: 'ok', xml: '<xml></xml>' })
                };
            }
            return { json: async () => ({ status: 'ok' }) };
        };

        window.fetch = global.fetch;

        const bridgeScript = fs.readFileSync(path.join(__dirname, '../common/webBridge.js'), 'utf8');
        window.eval(bridgeScript);
    });

    afterEach(() => {
        if (dom && dom.window) {
            dom.window.close();
        }
    });

    it('creates javaBridge on window object', () => {
        assert.ok(window.javaBridge, 'window.javaBridge should be defined');
        assert.strictEqual(typeof window.javaBridge.syncModel, 'function');
        assert.strictEqual(typeof window.javaBridge.debugStart, 'function');
    });

    it('syncModel updates last synced model', async () => {
        window.javaBridge.syncModel('<xml><block type="maze_moveForward"></block></xml>');
        await new Promise(r => setTimeout(r, 50));
        assert.ok(window.__lastSyncedXml);
    });

    it('local debugger computes step trace without error', () => {
        window.X = [[2, 1, 3]];
        const frame0Str = window.javaBridge.debugStart(0, 0, 1);
        const frame0 = JSON.parse(frame0Str);

        assert.strictEqual(frame0.q, 0);
        assert.strictEqual(frame0.s, 0);
        assert.strictEqual(frame0.paused, true);
    });

    it('reports an empty solutions cache as an empty list', () => {
        assert.strictEqual(window.javaBridge.listMomotSolutions(), '[]');
    });

    it('renders MoMoT solutions while the run is active and again when it finishes', async () => {
        const rendered = [];
        window.__momotRenderSolutions = function(data) { rendered.push(data); };

        let phase = 'running';
        const realSetTimeout = window.setTimeout.bind(window);
        const realClearTimeout = window.clearTimeout.bind(window);
        let pollCb = null;
        let retryCb = null;

        window.setInterval = function(cb) {
            pollCb = cb;
            return 41;
        };
        window.clearInterval = function(id) {
            if (id === 41) pollCb = null;
        };
        window.setTimeout = function(cb, ms) {
            if (ms === 150) return realSetTimeout(cb, 0);
            if (ms === 400) {
                retryCb = cb;
                return 42;
            }
            return realSetTimeout(cb, ms);
        };
        window.clearTimeout = function(id) {
            if (id === 42) {
                retryCb = null;
                return;
            }
            realClearTimeout(id);
        };

        const previousFetch = window.fetch;
        window.fetch = async (url) => {
            if (url.includes('/api/momot/run')) {
                return { json: async () => ({ status: 'ok', running: true }) };
            }
            if (url.includes('/api/momot/status')) {
                if (phase === 'running') {
                    return { json: async () => ({ running: true, status: 'Running', logs: ['tick'] }) };
                }
                return { json: async () => ({ running: false, status: 'Finished', logs: ['tick', 'done'] }) };
            }
            if (url.includes('/api/momot/solutions')) {
                return { json: async () => ([{ modelPath: 'live.xmi', objectiveLine: '-1 2 1 0' }]) };
            }
            return previousFetch(url);
        };
        global.fetch = window.fetch;

        window.javaBridge.runMomotWithParams(1, 20, 100, 1, 8);
        await new Promise(r => realSetTimeout(r, 30));
        assert.ok(pollCb, 'status polling should start after the run is accepted');

        const before = rendered.length;
        pollCb();
        await new Promise(r => realSetTimeout(r, 30));
        assert.ok(rendered.length > before, 'solutions render while the run is still active');
        assert.strictEqual(rendered[rendered.length - 1][0].modelPath, 'live.xmi');
        assert.ok(pollCb, 'polling continues while the run is active');

        phase = 'finished';
        pollCb();
        await new Promise(r => realSetTimeout(r, 30));
        assert.strictEqual(pollCb, null, 'polling stops when the run finishes');
        assert.ok(rendered.length > before + 1, 'solutions render again when the run finishes');
        assert.strictEqual(typeof retryCb, 'function', 'a follow-up solutions fetch is scheduled');

        const afterFinish = rendered.length;
        retryCb();
        await new Promise(r => realSetTimeout(r, 30));
        assert.ok(rendered.length > afterFinish, 'the delayed fetch renders the final solutions');
    });

    it('sets starting state immediately, polls status once on accept, and surfaces request errors', async () => {
        let statusLog = [];
        let progressStarted = null;
        let progressDoneCalled = false;

        window.__momotSetStatus = (msg) => { statusLog.push(msg); };
        window.__momotProgressStart = (runs, gens) => { progressStarted = { runs, gens }; };
        window.__momotProgressDone = () => { progressDoneCalled = true; };

        let runDeferred = null;
        const previousFetch = window.fetch;
        let statusFetched = false;

        window.fetch = async (url) => {
            if (url.includes('/api/momot/run')) {
                return new Promise((resolve) => {
                    runDeferred = () => resolve({ json: async () => ({ status: 'ok', running: true }) });
                });
            }
            if (url.includes('/api/momot/status')) {
                statusFetched = true;
                return { json: async () => ({ running: true, status: 'Waiting' }) };
            }
            if (url.includes('/api/momot/solutions')) {
                return { json: async () => [] };
            }
            return previousFetch(url);
        };
        global.fetch = window.fetch;

        // Start search (seed=1, pop=20, eval=100, runs=2, solLen=8) -> totalGens = Math.floor(100/20) = 5
        window.javaBridge.runMomotWithParams(1, 20, 100, 2, 8);

        // Synchronously in the same turn: progress started and status set
        assert.deepStrictEqual(progressStarted, { runs: 2, gens: 5 });
        assert.ok(statusLog.includes('Starting MoMoT...'), 'status should be set to Starting MoMoT... immediately');
        assert.strictEqual(statusFetched, false, 'status poll should not happen before run request resolves');

        // Resolve the run POST
        runDeferred();
        await new Promise(r => setTimeout(r, 40));

        // Immediate poll should have executed without manual setInterval tick
        assert.strictEqual(statusFetched, true, 'status should be polled immediately once run is accepted');
        assert.strictEqual(statusLog[statusLog.length - 1], 'MoMoT: Waiting', 'MoMoT: Waiting should replace Starting MoMoT...');

        // Test error handling on failed run POST
        statusLog = [];
        progressDoneCalled = false;
        window.fetch = async (url) => {
            if (url.includes('/api/momot/run')) {
                throw new Error('Connection refused');
            }
            return previousFetch(url);
        };
        global.fetch = window.fetch;

        window.javaBridge.runMomotWithParams(1, 20, 100, 1, 8);
        await new Promise(r => setTimeout(r, 40));

        assert.strictEqual(progressDoneCalled, true, 'progress bar should stop on run error');
        assert.ok(statusLog.some(msg => msg.includes('Run failed: Connection refused')), 'error must be surfaced on status line');

        // Test status polling error handling
        statusLog = [];
        window.fetch = async (url) => {
            if (url.includes('/api/momot/run')) {
                return { json: async () => ({ status: 'ok', running: true }) };
            }
            if (url.includes('/api/momot/status')) {
                throw new Error('503 Service Unavailable');
            }
            return previousFetch(url);
        };
        global.fetch = window.fetch;

        window.javaBridge.runMomotWithParams(1, 20, 100, 1, 8);
        await new Promise(r => setTimeout(r, 40));
        assert.ok(statusLog.some(msg => msg.includes('MoMoT status error: 503 Service Unavailable')), 'status poll failure must be surfaced on status line');
    });

    it('runMomotWithParams includes current XML, map, meta, and epoch in the run body', async () => {
        let runBody = null;
        window.BlocklyInterface = {
            getCode: () => '<xml><block type="maze_moveForward"></block></xml>'
        };

        const origFetch = window.fetch;
        window.fetch = async (url, options = {}) => {
            if (url.includes('/api/momot/run')) {
                runBody = JSON.parse(options.body);
                return { json: async () => ({ status: 'ok', running: true }) };
            }
            return origFetch(url, options);
        };
        global.fetch = window.fetch;

        window.javaBridge.runMomotWithParams(7, 30, 500, 1, 6);
        await new Promise(r => setTimeout(r, 50));

        assert.ok(runBody, 'MOMoT run request should have been made');
        assert.strictEqual(runBody.seed, 7);
        assert.strictEqual(runBody.populationSize, 30);
        assert.ok(runBody.epoch >= 1, 'Run body should contain sync epoch');
        assert.ok(runBody.xml.includes('maze_moveForward'), 'Run body should contain current workspace XML');
        assert.ok(runBody.map, 'Run body should contain active map');
        assert.ok(runBody.meta, 'Run body should contain level metadata');
    });

    it('sends empty workspace with allowEmpty only after the workspace stays empty', async () => {
        const syncCalls = [];
        window.K = 1;
        window.localStorage.setItem('maze1', '<xml><block type="maze_moveForward"></block></xml>');

        const origFetch = window.fetch;
        window.fetch = async (url, options = {}) => {
            if (url.includes('/api/session/sync')) {
                syncCalls.push(JSON.parse(options.body));
                return { json: async () => ({ status: 'ok' }) };
            }
            return origFetch(url, options);
        };
        global.fetch = window.fetch;

        // Simulate having had a program previously
        window.__lastSyncedXml = '<xml><block type="maze_moveForward"></block></xml>';
        window.__lastObservedXml = '<xml><block type="maze_moveForward"></block></xml>';

        // User empties the workspace
        window.BlocklyInterface = {
            getCode: () => '<xml></xml>'
        };

        // First poll: single empty check should be ignored as a possible transient snapshot
        window.__updateWorkspaceAndTrace();
        await new Promise(r => setTimeout(r, 20));
        assert.strictEqual(syncCalls.length, 0, 'First empty check must not trigger clear sync');

        // Second poll: workspace stays empty, so it is an intentional clear
        window.__updateWorkspaceAndTrace();
        await new Promise(r => setTimeout(r, 20));
        assert.ok(syncCalls.length > 0, 'Second empty check must trigger clear sync');
        const lastCall = syncCalls[syncCalls.length - 1];
        assert.strictEqual(lastCall.allowEmpty, true);
        assert.strictEqual(lastCall.xml, '');
        assert.strictEqual(window.localStorage.getItem('maze1'), null, 'localStorage maze cache must be removed');
        assert.strictEqual(window.sessionStorage.getItem('blocky_cleared_1'), '1', 'sessionStorage cleared flag must be set');
    });

    it('does not treat a workspace that has not loaded yet as an intentional clear', async () => {
        const syncCalls = [];
        window.K = 1;
        window.localStorage.setItem('maze1', '<xml><block type="maze_moveForward"></block></xml>');
        window.__lastSyncedXml = '';
        window.__lastObservedXml = '';
        delete window.BlocklyInterface;

        const origFetch = window.fetch;
        window.fetch = async (url, options = {}) => {
            if (url.includes('/api/session/sync')) {
                syncCalls.push(JSON.parse(options.body));
                return { json: async () => ({ status: 'ok' }) };
            }
            return origFetch(url, options);
        };
        global.fetch = window.fetch;

        window.__updateWorkspaceAndTrace();
        window.__updateWorkspaceAndTrace();
        window.__updateWorkspaceAndTrace();
        await new Promise(r => setTimeout(r, 20));

        assert.strictEqual(syncCalls.length, 0, 'Unloaded workspace must not send an empty sync');
        assert.strictEqual(window.localStorage.getItem('maze1'), '<xml><block type="maze_moveForward"></block></xml>');
        assert.strictEqual(window.sessionStorage.getItem('blocky_cleared_1'), null);
    });

    it('on load does not restore session state XML if blocky_cleared flag is set', async () => {
        let appliedXml = null;
        window.K = 1;
        window.sessionStorage.setItem('blocky_cleared_1', '1');
        window.localStorage.setItem('maze1', 'stale_maze_cache');

        window.BlocklyInterface = {
            setCode: (xml) => { appliedXml = xml; },
            getCode: () => ''
        };

        const syncCalls = [];
        const origFetch = window.fetch;
        window.fetch = async (url, options = {}) => {
            if (url.includes('/api/session/state')) {
                return {
                    json: async () => ({
                        status: 'ok',
                        levelId: 1,
                        grid: [[2, 1, 3]],
                        xml: '<xml><block type="maze_moveForward"></block></xml>'
                    })
                };
            }
            if (url.includes('/api/session/sync')) {
                syncCalls.push(JSON.parse(options.body));
                return { json: async () => ({ status: 'ok' }) };
            }
            return origFetch(url, options);
        };
        global.fetch = window.fetch;

        // Reload bridge script with session ID present
        window.localStorage.setItem('blocky_session_id', 'test-session-cleared');
        delete window.javaBridge;
        const bridgeScript = fs.readFileSync(path.join(__dirname, '../common/webBridge.js'), 'utf8');
        window.eval(bridgeScript);

        await new Promise(r => setTimeout(r, 50));

        assert.strictEqual(appliedXml, null, 'Session state XML must not be applied when cleared flag is set');
        assert.strictEqual(window.localStorage.getItem('maze1'), null, 'localStorage maze cache must be cleared');
        const emptySync = syncCalls.find(c => c.allowEmpty === true);
        assert.ok(emptySync, 'An empty sync with allowEmpty must be sent instead');
    });
});
