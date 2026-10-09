const { describe, it, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const { JSDOM } = require('jsdom');
const path = require('node:path');
const fs = require('node:fs');

describe('Level Navigation Step Test Suite', () => {
    let dom;
    let window;

    beforeEach(() => {
        dom = new JSDOM(`<!DOCTYPE html>
<html>
<body>
  <div id="blockly">
    <button id="runButton">Run Program</button>
  </div>
  <svg id="svgMaze">
    <image id="pegman"></image>
    <rect id="clipRect"></rect>
  </svg>
  <div data-id="block1">Move Forward 1</div>
  <div data-id="block2">Move Forward 2</div>
  <div data-id="block3">Turn Right</div>
  <div data-id="block4">Move Forward 3</div>
</body>
</html>`, {
            url: 'http://localhost/',
            runScripts: 'dangerously'
        });
        window = dom.window;
        global.window = window;
        global.document = window.document;

        // Level Grid (2x3):
        // (0,0)=START(2), (1,0)=PATH(1), (2,0)=PATH(1)
        // (0,1)=WALL(0),  (1,1)=WALL(0), (2,1)=GOAL(3)
        window.X = [
            [2, 1, 1],
            [0, 0, 3]
        ];
        window.nd = 0; window.od = 0; window.T = 1; // Start at (0,0) facing EAST (1)
        window.Q = 0; window.S = 0;
        window.Z = (q, s, d) => {
            window.Q = q;
            window.S = s;
        };

        // Construct block program: Move Forward -> Move Forward -> Turn Right -> Move Forward
        const block4 = {
            type: 'maze_moveForward',
            id: 'block4',
            getNextBlock: () => null
        };
        const block3 = {
            type: 'maze_turn',
            id: 'block3',
            getFieldValue: (field) => field === 'DIR' ? 'turnRight' : null,
            getNextBlock: () => block4
        };
        const block2 = {
            type: 'maze_moveForward',
            id: 'block2',
            getNextBlock: () => block3
        };
        const block1 = {
            type: 'maze_moveForward',
            id: 'block1',
            getNextBlock: () => block2
        };

        // Mock Blockly workspace returning block1 as top block
        window.Blockly = {
            getMainWorkspace: () => ({
                getTopBlocks: () => [block1]
            })
        };

        // Mock fetch API for session calls
        global.fetch = async (url, options = {}) => {
            if (url.includes('/api/session/new')) {
                return { json: async () => ({ sessionId: 'test-nav-session' }) };
            }
            if (url.includes('/api/session/state')) {
                return { json: async () => ({ status: 'ok', grid: window.X, startPos: { x: 0, y: 0 } }) };
            }
            return { json: async () => ({ status: 'ok' }) };
        };
        window.fetch = global.fetch;

        const bridgeScript = fs.readFileSync(path.join(__dirname, '../common/webBridge.js'), 'utf8');
        window.eval(bridgeScript);

        const overlayScript = fs.readFileSync(path.join(__dirname, '../common/blockyUIOverlay.js'), 'utf8');
        window.eval(overlayScript);
    });

    afterEach(() => {
        if (dom && dom.window) {
            dom.window.close();
        }
    });

    it('navigates pegman step-by-step through the level to the goal using the Step button', async () => {
        await new Promise(r => setTimeout(r, 150));

        const stepBtn = window.document.getElementById('debugStepButton');
        assert.ok(stepBtn, '#debugStepButton should exist in DOM');

        // Initial Start Position: Pegman at (0,0)
        assert.strictEqual(window.Q, 0);
        assert.strictEqual(window.S, 0);

        // --- STEP 1: Execute block1 (maze_moveForward) ---
        stepBtn.click();
        assert.strictEqual(window.Q, 1, 'Pegman should move forward to (1, 0)');
        assert.strictEqual(window.S, 0);
        assert.strictEqual(window.T, 1, 'Direction should remain EAST (1)');
        
        const block1El = window.document.querySelector('[data-id="block1"]');
        assert.ok(block1El.classList.contains('blocklySelected'), 'Block 1 should be highlighted');

        // --- STEP 2: Execute block2 (maze_moveForward) ---
        stepBtn.click();
        assert.strictEqual(window.Q, 2, 'Pegman should move forward to (2, 0)');
        assert.strictEqual(window.S, 0);
        
        const block2El = window.document.querySelector('[data-id="block2"]');
        assert.ok(block2El.classList.contains('blocklySelected'), 'Block 2 should be highlighted');

        // --- STEP 3: Execute block3 (maze_turn right) ---
        stepBtn.click();
        assert.strictEqual(window.Q, 2, 'Pegman position remains (2, 0)');
        assert.strictEqual(window.S, 0);
        assert.strictEqual(window.T, 2, 'Direction should turn right to SOUTH (2)');

        const block3El = window.document.querySelector('[data-id="block3"]');
        assert.ok(block3El.classList.contains('blocklySelected'), 'Block 3 should be highlighted');

        // --- STEP 4: Execute block4 (maze_moveForward into Goal) ---
        stepBtn.click();
        assert.strictEqual(window.Q, 2, 'Pegman should move down to (2, 1)');
        assert.strictEqual(window.S, 1);

        const block4El = window.document.querySelector('[data-id="block4"]');
        assert.ok(block4El.classList.contains('blocklySelected'), 'Block 4 should be highlighted');

        // Execution log should contain the step traces
        const logBody = window.document.getElementById('__execLogBody');
        assert.ok(logBody.textContent.includes('Move forward -> (1,0)'));
        assert.ok(logBody.textContent.includes('Turn right -> dir=SOUTH'));
        assert.ok(logBody.textContent.includes('Move forward -> (2,1)'));

        // Since the goal is reached (result: 'WON'), step button should now be disabled
        assert.strictEqual(stepBtn.disabled, true, 'Step button should be disabled when goal is reached (terminal state)');
    });
});
