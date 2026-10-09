const { describe, it, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const { JSDOM } = require('jsdom');
const path = require('node:path');
const fs = require('node:fs');

describe('Level Timer and Activity Headers Test Suite', () => {
    let dom;
    let window;
    let capturedRequests = [];

    beforeEach(() => {
        capturedRequests = [];
        dom = new JSDOM('<!DOCTYPE html><html><body><button id="runButton">Run Program</button><button id="resetButton" style="display: none">Reset</button><div id="blockly"></div><svg id="svgMaze"></svg></body></html>', {
            url: 'http://localhost/?level=3',
            runScripts: 'dangerously'
        });
        window = dom.window;
        global.window = window;
        global.document = window.document;
        global.sessionStorage = window.sessionStorage;
        global.localStorage = window.localStorage;

        // Set session ID in sessionStorage
        window.sessionStorage.setItem('blocky_level_timer_session_id', 'test_user_sess_999');
        window.sessionStorage.setItem('blocky_level_timer_user_id', 'alice');
        window.sessionStorage.setItem('blocky_level_timer_current_level', '3');

        // Mock fetch
        window.fetch = async (url, options = {}) => {
            capturedRequests.push({ url, options });
            if (url.includes('/api/session/new')) {
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({ status: 'ok', sessionId: 'test-session-123' })
                };
            }
            return {
                ok: true,
                status: 200,
                json: async () => ({ status: 'ok', levels: {} })
            };
        };
        global.fetch = window.fetch;
    });

    afterEach(() => {
        if (window && window.__levelTimerCleanup) {
            try { window.__levelTimerCleanup(); } catch (e) {}
        }
        if (dom && dom.window) {
            dom.window.close();
        }
    });

    it('records program run when runButton is clicked via levelTimer hook', () => {
        window.BlocklyInterface = {
            getCode: () => '<xml><block type="maze_moveForward"></block></xml>'
        };

        const timerScript = fs.readFileSync(path.join(__dirname, '../common/levelTimer.js'), 'utf8');
        window.eval(timerScript);

        const runBtn = window.document.getElementById('runButton');
        assert.ok(runBtn, 'runButton should exist');

        runBtn.click();

        const simCalls = capturedRequests.filter(r => r.url.includes('/api/simulation/run'));
        assert.strictEqual(simCalls.length, 1, 'Should have triggered 1 simulation/run call');
        const req = simCalls[0];
        assert.strictEqual(req.options.headers['X-Timer-Session-ID'], 'test_user_sess_999');
        assert.strictEqual(req.options.headers['X-Level-ID'], '3');
        const body = JSON.parse(req.options.body);
        assert.strictEqual(body.timerSessionId, 'test_user_sess_999');
        assert.strictEqual(body.level, 3);
        assert.strictEqual(body.recordExecution, true);
        assert.ok(body.timestamp, 'timestamp should be present');
        assert.strictEqual(body.xml, '<xml><block type="maze_moveForward"></block></xml>');
    });

    it('falls back to localStorage workspace XML when BlocklyInterface is unavailable', () => {
        window.localStorage.setItem('maze3', '<xml><block type="maze_turn"><field name="DIR">turnLeft</field></block></xml>');

        const timerScript = fs.readFileSync(path.join(__dirname, '../common/levelTimer.js'), 'utf8');
        window.eval(timerScript);

        const runBtn = window.document.getElementById('runButton');
        runBtn.click();

        const simCalls = capturedRequests.filter(r => r.url.includes('/api/simulation/run'));
        assert.strictEqual(simCalls.length, 1);
        const body = JSON.parse(simCalls[0].options.body);
        assert.strictEqual(body.recordExecution, true);
        assert.strictEqual(body.xml, '<xml><block type="maze_turn"><field name="DIR">turnLeft</field></block></xml>');
    });

    it('does not count a Reset click or the Run click the browser fires right after it', () => {
        const timerScript = fs.readFileSync(path.join(__dirname, '../common/levelTimer.js'), 'utf8');
        window.eval(timerScript);

        const runBtn = window.document.getElementById('runButton');
        const resetBtn = window.document.getElementById('resetButton');
        runBtn.click();
        runBtn.style.display = 'none';
        resetBtn.style.display = 'inline';
        resetBtn.click();
        resetBtn.style.display = 'none';
        runBtn.style.display = 'inline';
        runBtn.click();

        const simCalls = capturedRequests.filter(r => r.url.includes('/api/simulation/run'));
        assert.strictEqual(simCalls.length, 1, 'Reset and its follow-up Run click must not count');
    });

    it('attaches timer headers and payload to MOMoT actions in webBridge', async () => {
        const bridgeScript = fs.readFileSync(path.join(__dirname, '../common/webBridge.js'), 'utf8');
        window.eval(bridgeScript);

        assert.ok(window.javaBridge, 'javaBridge should exist');

        // Test runMomotWithParams
        window.javaBridge.runMomotWithParams(42, 20, 1000, 1, 10);
        await new Promise(r => setTimeout(r, 250));
        const momotCalls = capturedRequests.filter(r => r.url.includes('/api/momot/run'));
        assert.strictEqual(momotCalls.length, 1);
        assert.strictEqual(momotCalls[0].options.headers['X-Timer-Session-ID'], 'test_user_sess_999');
        assert.strictEqual(momotCalls[0].options.headers['X-Level-ID'], '3');

        // Test loadMomotSolution
        window.javaBridge.loadMomotSolution('models/sol1.xmi');
        await new Promise(r => setTimeout(r, 50));
        const loadCalls = capturedRequests.filter(r => r.url.includes('/api/momot/load'));
        assert.strictEqual(loadCalls.length, 1);
        assert.strictEqual(loadCalls[0].options.headers['X-Timer-Session-ID'], 'test_user_sess_999');
        assert.strictEqual(loadCalls[0].options.headers['X-Level-ID'], '3');

        // Test teleportPegman (Direct Manipulation)
        window.javaBridge.teleportPegman(1, 2, 0);
        await new Promise(r => setTimeout(r, 50));
        const dmCalls = capturedRequests.filter(r => r.url.includes('/api/dm/request'));
        assert.strictEqual(dmCalls.length, 1);
        assert.strictEqual(dmCalls[0].options.headers['X-Timer-Session-ID'], 'test_user_sess_999');
        assert.strictEqual(dmCalls[0].options.headers['X-Level-ID'], '3');
    });

    it('locks header level links by turning anchors into non-navigable spans', () => {
        const headerDiv = window.document.createElement('div');
        headerDiv.innerHTML = [
            '<span class="level_number level_done" id="level3">3</span>',
            '<a class="level_dot" id="level1" href="?level=1"></a>',
            '<a class="level_dot" id="level2" href="?level=2"></a>',
            '<a class="level_number" id="level10" href="?level=10">10</a>'
        ].join('');
        window.document.body.appendChild(headerDiv);

        const timerScript = fs.readFileSync(path.join(__dirname, '../common/levelTimer.js'), 'utf8');
        window.eval(timerScript);

        const remainingAnchors = window.document.querySelectorAll('a.level_dot, a.level_number');
        assert.strictEqual(remainingAnchors.length, 0, 'No clickable anchor level links should remain');

        const spanDots = window.document.querySelectorAll('span.level_dot');
        assert.strictEqual(spanDots.length, 2, 'Should convert dots to span elements');
        assert.strictEqual(spanDots[0].id, 'level1');
        assert.strictEqual(spanDots[1].id, 'level2');

        const span10 = window.document.getElementById('level10');
        assert.ok(span10, 'level10 element should exist');
        assert.strictEqual(span10.tagName.toLowerCase(), 'span', 'level10 should be a span, not anchor');
        assert.strictEqual(span10.textContent, '10');
        assert.strictEqual(span10.getAttribute('href'), null, 'level10 should have no href');
    });

    it('suppresses goal win dialog and skip dialog, showing non-blocking goal note on timer widget', () => {
        window.BlocklyDialogs = {
            Xy: () => { throw new Error('Original win dialog should not be called'); },
            Zs: () => { throw new Error('Original skip dialog should not be called'); },
            kc: () => {}
        };
        window.BlocklyInterface = {
            Um: () => { throw new Error('Original Um should not be called'); }
        };

        const timerScript = fs.readFileSync(path.join(__dirname, '../common/levelTimer.js'), 'utf8');
        window.eval(timerScript);

        const goalNote = window.document.getElementById('timerGoalNote');
        assert.ok(goalNote, 'Goal note element should exist in timer widget');
        assert.strictEqual(goalNote.style.display, '', 'Goal note initially hidden (default css display)');

        // Simulate goal reached trigger
        window.BlocklyDialogs.Xy();
        assert.strictEqual(goalNote.style.display, 'inline-block', 'Goal note should be visible after reaching goal');

        // Calling Um and Zs should be safe no-ops
        assert.doesNotThrow(() => {
            window.BlocklyInterface.Um();
            window.BlocklyDialogs.Zs();
        });

        // Maze assigns BlocklyInterface first, then overwrites Um with next-level navigation.
        const iface = {};
        window.BlocklyInterface = iface;
        iface.Um = () => { throw new Error('Later Um overwrite must not navigate'); };
        assert.doesNotThrow(() => iface.Um());

        const dialogs = {
            Xy: () => { throw new Error('Original win dialog should not be called'); },
            kc: () => {}
        };
        window.BlocklyDialogs = dialogs;
        dialogs.Xy = () => { throw new Error('Later Xy overwrite must not open the dialog'); };
        assert.doesNotThrow(() => dialogs.Xy());
        assert.strictEqual(goalNote.style.display, 'inline-block');
    });

    it('provides Next task button on levels 1-9 that confirms before incrementing stored level', () => {
        let navigatedUrl = null;
        window.__levelTimerNavigate = (url) => { navigatedUrl = url; };

        const timerScript = fs.readFileSync(path.join(__dirname, '../common/levelTimer.js'), 'utf8');
        window.eval(timerScript);

        const nextBtn = window.document.getElementById('timerNextTaskBtn');
        assert.ok(nextBtn, 'Next task button should exist on level 3');
        assert.strictEqual(nextBtn.textContent, 'Next task');

        const finishBtn = window.document.getElementById('timerFinishSessionBtn');
        assert.strictEqual(finishBtn, null, 'Finish task button should not exist on level 3');

        // First click must show the dialog and must not change the stored level or navigate
        nextBtn.click();

        const confirmModal = window.document.getElementById('levelTimerConfirmModal');
        assert.ok(confirmModal, 'Confirm modal should exist');
        assert.strictEqual(confirmModal.style.display, 'flex', 'Confirm modal should be visible');
        assert.ok(confirmModal.textContent.includes('Go to the next task?'));

        let storedLevel = window.sessionStorage.getItem('blocky_level_timer_current_level');
        assert.strictEqual(storedLevel, '3', 'First click must not change stored level');
        assert.strictEqual(navigatedUrl, null, 'First click must not navigate');

        // Confirming must store level 4 and navigate to level=4
        const confirmNextBtn = window.document.getElementById('levelTimerConfirmNextBtn');
        assert.ok(confirmNextBtn, 'Confirm Next task button should exist');
        confirmNextBtn.click();

        assert.strictEqual(confirmModal.style.display, 'none', 'Confirm modal should close upon confirming');
        storedLevel = window.sessionStorage.getItem('blocky_level_timer_current_level');
        assert.strictEqual(storedLevel, '4', 'Confirming Next task must store level 4');
        assert.ok(navigatedUrl && navigatedUrl.includes('level=4'), 'Confirming Next task must navigate to level 4');
    });

    it('closes dialog and does not navigate when Stay is clicked', () => {
        let navigatedUrl = null;
        window.__levelTimerNavigate = (url) => { navigatedUrl = url; };

        const timerScript = fs.readFileSync(path.join(__dirname, '../common/levelTimer.js'), 'utf8');
        window.eval(timerScript);

        const nextBtn = window.document.getElementById('timerNextTaskBtn');
        nextBtn.click();

        const confirmModal = window.document.getElementById('levelTimerConfirmModal');
        assert.ok(confirmModal);
        assert.strictEqual(confirmModal.style.display, 'flex');

        const stayBtn = window.document.getElementById('levelTimerConfirmStayBtn');
        assert.ok(stayBtn, 'Stay button should exist');
        stayBtn.click();

        assert.strictEqual(confirmModal.style.display, 'none', 'Stay must close the dialog');
        const storedLevel = window.sessionStorage.getItem('blocky_level_timer_current_level');
        assert.strictEqual(storedLevel, '3', 'Stored level must remain 3 after Stay');
        assert.strictEqual(navigatedUrl, null, 'Stay must not navigate');
    });

    it('provides Finish task button on level 10 that opens session report', () => {
        const level10Dom = new JSDOM('<!DOCTYPE html><html><body><div id="blockly"></div></body></html>', {
            url: 'http://localhost/?level=10',
            runScripts: 'dangerously'
        });
        const l10Win = level10Dom.window;
        global.window = l10Win;
        global.document = l10Win.document;
        global.sessionStorage = l10Win.sessionStorage;
        global.localStorage = l10Win.localStorage;

        l10Win.sessionStorage.setItem('blocky_level_timer_session_id', 'test_sess_10');
        l10Win.sessionStorage.setItem('blocky_level_timer_user_id', 'bob');
        l10Win.sessionStorage.setItem('blocky_level_timer_current_level', '10');
        l10Win.fetch = async () => ({ ok: true, status: 200, json: async () => ({}) });
        global.fetch = l10Win.fetch;

        const timerScript = fs.readFileSync(path.join(__dirname, '../common/levelTimer.js'), 'utf8');
        l10Win.eval(timerScript);

        const finBtn = l10Win.document.getElementById('timerFinishSessionBtn');
        assert.ok(finBtn, 'Finish task button should exist on level 10');
        assert.strictEqual(finBtn.textContent, 'Finish task');

        const nextBtn = l10Win.document.getElementById('timerNextTaskBtn');
        assert.strictEqual(nextBtn, null, 'Next task button should not exist on level 10');

        finBtn.click();

        const reportModal = l10Win.document.getElementById('levelTimerReportModal');
        assert.ok(reportModal, 'Session report modal should exist');
        assert.strictEqual(reportModal.style.display, 'flex');
        assert.ok(reportModal.textContent.includes('Session Timing Report'));

        if (l10Win.__levelTimerCleanup) l10Win.__levelTimerCleanup();
        level10Dom.window.close();
    });

    it('redirects URL to stored level if URL level does not match stored level', () => {
        let replacedUrl = null;
        const mismatchDom = new JSDOM('<!DOCTYPE html><html><body></body></html>', {
            url: 'http://localhost/?lang=en&level=7&skin=1',
            runScripts: 'dangerously'
        });
        const mmWin = mismatchDom.window;
        global.window = mmWin;
        global.document = mmWin.document;
        global.sessionStorage = mmWin.sessionStorage;
        global.localStorage = mmWin.localStorage;

        mmWin.sessionStorage.setItem('blocky_level_timer_session_id', 'test_sess');
        mmWin.sessionStorage.setItem('blocky_level_timer_current_level', '2');
        mmWin.__levelTimerNavigate = (url) => { replacedUrl = url; };

        const timerScript = fs.readFileSync(path.join(__dirname, '../common/levelTimer.js'), 'utf8');
        mmWin.eval(timerScript);

        assert.ok(replacedUrl, 'Should have called navigation redirect');
        assert.ok(replacedUrl.includes('level=2'), 'Should redirect to stored level 2');
        assert.ok(replacedUrl.includes('lang=en'), 'Should preserve lang=en');
        assert.ok(replacedUrl.includes('skin=1'), 'Should preserve skin=1');

        if (mmWin.__levelTimerCleanup) mmWin.__levelTimerCleanup();
        mismatchDom.window.close();
    });

    it('fresh session without stored level defaults to level 1 and redirects', () => {
        let replacedUrl = null;
        const freshDom = new JSDOM('<!DOCTYPE html><html><body></body></html>', {
            url: 'http://localhost/?level=8',
            runScripts: 'dangerously'
        });
        const freshWin = freshDom.window;
        global.window = freshWin;
        global.document = freshWin.document;
        global.sessionStorage = freshWin.sessionStorage;
        global.localStorage = freshWin.localStorage;

        freshWin.__levelTimerNavigate = (url) => { replacedUrl = url; };

        const timerScript = fs.readFileSync(path.join(__dirname, '../common/levelTimer.js'), 'utf8');
        freshWin.eval(timerScript);

        assert.strictEqual(freshWin.sessionStorage.getItem('blocky_level_timer_current_level'), '1', 'Should set stored level to 1');
        assert.ok(replacedUrl, 'Should have called navigation redirect');
        assert.ok(replacedUrl.includes('level=1'), 'Should redirect to level 1');

        if (freshWin.__levelTimerCleanup) freshWin.__levelTimerCleanup();
        freshDom.window.close();
    });
});
