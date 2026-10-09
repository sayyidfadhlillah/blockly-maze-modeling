const { describe, it, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const { JSDOM } = require('jsdom');
const path = require('node:path');
const fs = require('node:fs');

describe('directManipulation.test.js Test Suite', () => {
    let dom;
    let window;
    let teleportCalls;

    beforeEach(() => {
        dom = new JSDOM(`<!DOCTYPE html>
<html>
<body>
  <div id="blockly">
    <button id="runButton">Run</button>
  </div>
  <svg id="svgMaze" style="width:400px;height:400px;"></svg>
</body>
</html>`, {
            url: 'http://localhost/',
            runScripts: 'dangerously'
        });
        window = dom.window;
        global.window = window;
        global.document = window.document;

        // Mock 4x4 grid map: 1=path, 0=wall, 3=goal, 2=start
        window.X = [
            [2, 1, 1, 0],
            [0, 0, 1, 0],
            [0, 0, 1, 3],
            [0, 0, 0, 0]
        ];
        window.Q = 0; window.S = 0; window.T = 1;

        teleportCalls = [];
        window.javaBridge = {
            teleportPegman: (q, s, t) => {
                teleportCalls.push({ q, s, t });
            }
        };

        const overlayScript = fs.readFileSync(path.join(__dirname, '../common/blockyUIOverlay.js'), 'utf8');
        window.eval(overlayScript);
    });

    afterEach(() => {
        if (dom && dom.window) {
            dom.window.close();
        }
    });

    it('calculates grid cell accurately on Direct Manipulation click and teleports pegman', async () => {
        await new Promise(r => setTimeout(r, 150));
        const dmBtn = window.document.getElementById('directManipulationButton');
        assert.ok(dmBtn);

        // Enable Direct Manipulation mode
        dmBtn.click();
        assert.strictEqual(window.__dmActive, true);

        const svg = window.document.getElementById('svgMaze');
        // Mock getBoundingClientRect
        svg.getBoundingClientRect = () => ({
            left: 0, top: 0, width: 400, height: 400, right: 400, bottom: 400
        });

        // Click cell at (2, 0) -> x = 225, y = 25 (out of 400 width/height for 4 cols/rows)
        const clickEv = new window.MouseEvent('click', {
            clientX: 225,
            clientY: 25,
            bubbles: true
        });

        svg.dispatchEvent(clickEv);

        assert.strictEqual(teleportCalls.length, 1);
        assert.strictEqual(teleportCalls[0].q, 2);
        assert.strictEqual(teleportCalls[0].s, 0);
    });

    it('updates grid, teleports pegman, and auto-starts MoMoT run when pegman is placed', async () => {
        await new Promise(r => setTimeout(r, 150));
        let runCalls = [];
        window.javaBridge.runMomotWithParams = (seed, pop, evalVal, runs, solLen) => {
            runCalls.push({ seed, pop, evalVal, runs, solLen });
        };

        const dmBtn = window.document.getElementById('directManipulationButton');
        dmBtn.click();
        const svg = window.document.getElementById('svgMaze');
        svg.getBoundingClientRect = () => ({
            left: 0, top: 0, width: 400, height: 400, right: 400, bottom: 400
        });

        // Click cell (2, 0)
        const clickEv = new window.MouseEvent('click', { clientX: 225, clientY: 25, bubbles: true });
        svg.dispatchEvent(clickEv);

        // Clicked cell should now be goal (3) and old goal (2, 2) should now be path (1)
        assert.strictEqual(window.X[0][2], 3);
        assert.strictEqual(window.X[2][2], 1);
        assert.strictEqual(window.od.x, 2);
        assert.strictEqual(window.od.y, 0);

        // MoMoT run was automatically triggered
        assert.strictEqual(runCalls.length, 1);
        assert.strictEqual(runCalls[0].seed, 0);
        assert.strictEqual(runCalls[0].pop, 50);
        assert.strictEqual(runCalls[0].runs, 10);
    });

    it('stops in-flight search first and awaits placement promise before auto-starting', async () => {
        await new Promise(r => setTimeout(r, 150));
        let eventOrder = [];
        let resolveTeleport;

        window.javaBridge.isMomotRunning = () => true;
        window.javaBridge.stopMomotRun = () => {
            eventOrder.push('stopMomotRun');
            return Promise.resolve();
        };
        window.javaBridge.teleportPegman = (q, s, t) => {
            eventOrder.push('teleportPegman');
            return new Promise((resolve) => {
                resolveTeleport = resolve;
            });
        };
        window.javaBridge.runMomotWithParams = () => {
            eventOrder.push('runMomotWithParams');
        };

        const dmBtn = window.document.getElementById('directManipulationButton');
        dmBtn.click();
        const svg = window.document.getElementById('svgMaze');
        svg.getBoundingClientRect = () => ({
            left: 0, top: 0, width: 400, height: 400, right: 400, bottom: 400
        });

        // Click cell (2, 0)
        const clickEv = new window.MouseEvent('click', { clientX: 225, clientY: 25, bubbles: true });
        svg.dispatchEvent(clickEv);

        await new Promise(r => setTimeout(r, 20));
        assert.deepStrictEqual(eventOrder, ['stopMomotRun', 'teleportPegman']);

        // Now resolve teleport promise
        resolveTeleport();
        await new Promise(r => setTimeout(r, 20));

        assert.deepStrictEqual(eventOrder, ['stopMomotRun', 'teleportPegman', 'runMomotWithParams']);
        assert.strictEqual(window.X[0][2], 3);
        assert.strictEqual(window.X[2][2], 1);
    });
});
