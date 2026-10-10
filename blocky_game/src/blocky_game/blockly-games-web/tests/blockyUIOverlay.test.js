const { describe, it, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const { JSDOM } = require('jsdom');
const path = require('node:path');
const fs = require('node:fs');

describe('blockyUIOverlay.js Test Suite', () => {
    let dom;
    let window;

    beforeEach(() => {
        dom = new JSDOM(`<!DOCTYPE html>
<html>
<body>
  <div id="blockly">
    <button id="runButton">Run Program</button>
  </div>
  <svg id="svgMaze"></svg>
</body>
</html>`, {
            url: 'http://localhost/',
            runScripts: 'dangerously'
        });
        window = dom.window;
        global.window = window;
        global.document = window.document;

        // Mock window.X grid
        window.X = [[2, 1, 3]];
        window.Q = 0; window.S = 0; window.T = 1;

        const overlayScript = fs.readFileSync(path.join(__dirname, '../common/blockyUIOverlay.js'), 'utf8');
        window.eval(overlayScript);
    });

    afterEach(() => {
        if (dom && dom.window) {
            dom.window.close();
        }
    });

    it('injects overlays and debugger buttons into DOM', async () => {
        await new Promise(r => setTimeout(r, 150));
        const loadingOverlay = window.document.getElementById('__lvlLoadingOverlay');
        assert.ok(loadingOverlay, '#__lvlLoadingOverlay should exist');

        const execLogPanel = window.document.getElementById('__execLogPanel');
        assert.ok(execLogPanel, '#__execLogPanel should exist');

        const momotPanel = window.document.getElementById('__momotPanel');
        assert.ok(momotPanel, '#__momotPanel should exist');

        const pauseBtn = window.document.getElementById('debugPauseResumeButton');
        const stepBtn = window.document.getElementById('debugStepButton');
        const stopBtn = window.document.getElementById('debugStopButton');
        const skipBtn = window.document.getElementById('debugSkipEndButton');
        const dmBtn = window.document.getElementById('directManipulationButton');

        assert.ok(pauseBtn, '#debugPauseResumeButton should exist');
        assert.ok(stepBtn, '#debugStepButton should exist');
        assert.ok(stopBtn, '#debugStopButton should exist');
        assert.ok(skipBtn, '#debugSkipEndButton should exist');
        assert.ok(dmBtn, '#directManipulationButton should exist');
    });

    it('execution log append and clear works properly', async () => {
        await new Promise(r => setTimeout(r, 150));
        assert.ok(typeof window.__execLogAppend === 'function');
        window.__execLogAppend(['Line 1', 'Line 2']);

        const body = window.document.getElementById('__execLogBody');
        assert.ok(body.textContent.includes('Line 1'));
        assert.ok(body.textContent.includes('Line 2'));

        window.__execLogClear();
        assert.strictEqual(body.textContent, '');
    });

    it('handles panel drag and resize mousedown events', async () => {
        await new Promise(r => setTimeout(r, 150));
        const header = window.document.getElementById('__execLogHeader');
        assert.ok(header);

        const mouseDownEv = new window.MouseEvent('mousedown', { clientX: 100, clientY: 100, button: 0 });
        header.dispatchEvent(mouseDownEv);

        const mouseMoveEv = new window.MouseEvent('mousemove', { clientX: 150, clientY: 150 });
        window.document.dispatchEvent(mouseMoveEv);

        const mouseUpEv = new window.MouseEvent('mouseup', {});
        window.document.dispatchEvent(mouseUpEv);
    });

    it('loads solution directly on single click and does not require double click', async () => {
        await new Promise(r => setTimeout(r, 150));
        let loadedPath = null;
        window.javaBridge = {
            loadMomotSolution: (path) => {
                loadedPath = path;
            },
            getSolutionPath: () => '[[0,0],[0,1]]'
        };

        assert.ok(typeof window.__momotRenderSolutions === 'function');
        window.__momotRenderSolutions([
            { modelPath: 'models/sol_test.xmi', objectiveLine: '-1.0 4.0' }
        ]);

        const list = window.document.getElementById('__momotList');
        assert.ok(list);
        const tr = list.querySelector('tbody tr');
        assert.ok(tr, 'Solution row should be rendered');

        tr.click();
        assert.strictEqual(loadedPath, 'models/sol_test.xmi', 'Single click must immediately call loadMomotSolution');
    });

    it('collapses MoMoT parameters behind gear button on header', async () => {
        await new Promise(r => setTimeout(r, 150));
        const settings = window.document.getElementById('__momotSettings');
        assert.ok(settings, '#__momotSettings should exist');
        assert.strictEqual(settings.style.display, 'none', 'Parameters row should be hidden on load');

        const runBtn = window.document.getElementById('__momotRunBtn');
        const stopBtn = window.document.getElementById('__momotStopBtn');
        const refreshBtn = window.document.getElementById('__momotRefreshBtn');
        const clearBtn = window.document.getElementById('__momotClearOverlayBtn');
        const gearBtn = window.document.getElementById('__momotGearBtn');
        const closeBtn = window.document.getElementById('__momotCloseBtn');

        assert.ok(runBtn, '#__momotRunBtn should exist');
        assert.ok(stopBtn, '#__momotStopBtn should exist');
        assert.strictEqual(refreshBtn, null, 'Refresh button was removed (the table refreshes itself)');
        assert.ok(clearBtn, '#__momotClearOverlayBtn (Clear path) should exist');
        assert.ok(gearBtn, '#__momotGearBtn should exist');
        assert.ok(closeBtn, '#__momotCloseBtn should exist');

        assert.strictEqual(runBtn.style.display, 'none', 'Run button should be hidden (search starts from the Direct Manipulation marker)');
        assert.notStrictEqual(stopBtn.style.display, 'none', 'Stop button should be visible');

        // Inputs should be in the DOM with defaults
        const inpSeed = window.document.getElementById('__momotInpSeed');
        const inpPop = window.document.getElementById('__momotInpPop');
        const inpIter = window.document.getElementById('__momotInpIter');
        const inpRuns = window.document.getElementById('__momotInpRuns');
        const inpSolLen = window.document.getElementById('__momotInpSolLen');

        assert.ok(inpSeed, 'inpSeed should exist in DOM');
        assert.strictEqual(inpSeed.value, '0');
        assert.ok(inpPop, 'inpPop should exist in DOM');
        assert.strictEqual(inpPop.value, '50');
        assert.ok(inpIter, 'inpIter should exist in DOM');
        assert.strictEqual(inpIter.value, '40');
        assert.ok(inpRuns, 'inpRuns should exist in DOM');
        assert.strictEqual(inpRuns.value, '10');
        assert.ok(inpSolLen, 'inpSolLen should exist in DOM');
        assert.strictEqual(inpSolLen.value, '10');

        // Clicking gear shows inputs
        gearBtn.click();
        assert.strictEqual(settings.style.display, 'flex', 'Gear click should show parameters row');

        // Clicking gear again collapses inputs
        gearBtn.click();
        assert.strictEqual(settings.style.display, 'none', 'Second gear click should collapse parameters row');
    });

    it('hides log by default, toggles it via header Log button, and omits Load button and Model column', async () => {
        await new Promise(r => setTimeout(r, 150));
        const loadBtn = window.document.getElementById('__momotLoadBtn');
        const actions = window.document.getElementById('__momotActions');
        assert.strictEqual(loadBtn, null, '#__momotLoadBtn should be removed');
        assert.strictEqual(actions, null, '#__momotActions should be removed');

        const log = window.document.getElementById('__momotLog');
        assert.ok(log, '#__momotLog should exist');
        assert.strictEqual(log.style.display, 'none', 'Log should start hidden');

        const logToggleBtn = window.document.getElementById('__momotLogToggleBtn');
        assert.ok(logToggleBtn, '#__momotLogToggleBtn should exist in header');
        assert.strictEqual(logToggleBtn.textContent, 'Log');

        logToggleBtn.click();
        assert.strictEqual(log.style.display, 'block', 'Clicking Log button should show log');
        assert.strictEqual(logToggleBtn.textContent, 'Hide Log');

        logToggleBtn.click();
        assert.strictEqual(log.style.display, 'none', 'Clicking again should hide log');
        assert.strictEqual(logToggleBtn.textContent, 'Log');

        const status = window.document.getElementById('__momotStatus');
        assert.strictEqual(status.textContent, 'Place the pegman to start search.');

        // Render solution and verify Model column does not exist
        window.__momotRenderSolutions([
            { modelPath: 'models/sol_test.xmi', objectiveLine: '-1.0 4.0 5.0 0.0 3.0' }
        ]);
        const list = window.document.getElementById('__momotList');
        const ths = Array.from(list.querySelectorAll('thead th')).map(th => th.textContent.trim());
        assert.ok(!ths.some(t => t.startsWith('Model')), 'Table headers should not contain Model column');

        const tds = list.querySelectorAll('tbody tr td');
        assert.strictEqual(tds.length, 5, 'Should have exactly 5 objective columns and no Model column');
    });
});
