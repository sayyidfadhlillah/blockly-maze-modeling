const { describe, it, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const { JSDOM } = require('jsdom');
const path = require('node:path');
const fs = require('node:fs');

describe('clientDebuggerUI.test.js Test Suite', () => {
    let dom;
    let window;

    beforeEach(() => {
        dom = new JSDOM(`<!DOCTYPE html>
<html>
<body>
  <div id="blockly">
    <button id="runButton">Run</button>
  </div>
  <svg id="svgMaze">
    <image id="pegman"></image>
    <rect id="clipRect"></rect>
  </svg>
  <div data-id="block1">Block 1</div>
</body>
</html>`, {
            url: 'http://localhost/',
            runScripts: 'dangerously'
        });
        window = dom.window;
        global.window = window;
        global.document = window.document;

        // Mock 3x1 map grid
        window.X = [[2, 1, 3]];
        window.nd = 0; window.od = 0; window.T = 1;
        window.Q = 0; window.S = 0;
        window.Z = (q, s, d) => {
            window.Q = q; window.S = s;
        };

        // Mock Blockly workspace
        window.Blockly = {
            getMainWorkspace: () => ({
                getTopBlocks: () => [
                    {
                        type: 'maze_moveForward',
                        id: 'block1',
                        getNextBlock: () => null
                    }
                ]
            })
        };

        // Mock fetch
        global.fetch = async (url, options = {}) => {
            if (url.includes('/api/session/new')) {
                return { json: async () => ({ sessionId: 'test-session-123' }) };
            }
            if (url.includes('/api/session/state')) {
                return { json: async () => ({ status: 'ok', grid: [[2, 1, 3]] }) };
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

    it('steps locally in JS, highlights active block, and updates pegman position', async () => {
        await new Promise(r => setTimeout(r, 150));
        const stepBtn = window.document.getElementById('debugStepButton');
        assert.ok(stepBtn, '#debugStepButton should exist');

        // Click Step button
        stepBtn.click();

        // Pegman should have moved to (1, 0)
        assert.strictEqual(window.Q, 1);
        assert.strictEqual(window.S, 0);

        // Active block element should have blocklySelected CSS class
        const blockEl = window.document.querySelector('[data-id="block1"]');
        assert.ok(blockEl.classList.contains('blocklySelected'), 'Active block should have blocklySelected class');
    });

    it('skip to end jumps to final state locally in JS', async () => {
        await new Promise(r => setTimeout(r, 150));
        const skipBtn = window.document.getElementById('debugSkipEndButton');
        assert.ok(skipBtn, '#debugSkipEndButton should exist');

        skipBtn.click();

        assert.strictEqual(window.Q, 1);
        assert.strictEqual(window.S, 0);
    });

    it('handles maze_ifElse with empty DO statement correctly', async () => {
        const xml = `<xml xmlns="https://developers.google.com/blockly/xml">
          <block type="maze_ifElse">
            <field name="DIR">isPathLeft</field>
            <statement name="DO"></statement>
            <statement name="ELSE">
              <block type="maze_turn"><field name="DIR">turnRight</field></block>
            </statement>
            <next>
              <block type="maze_turn"><field name="DIR">turnLeft</field></block>
            </next>
          </block>
        </xml>`;

        window.BlocklyInterface = {
            getCode: () => xml,
            getWorkspace: () => null
        };

        // Grid 3x3: (1,1) is START (2), (1,0) is PATH (1) [NORTH of (1,1)]
        // At (1,1) facing EAST (1), left is NORTH (1,0) which is PATH!
        // So isPathLeft is TRUE.
        // With DO empty, it should NOT run ELSE (turnRight)!
        window.X = [[0, 1, 0], [0, 2, 1], [0, 0, 0]];
        window.nd = { x: 1, y: 1 };
        window.Q = 1; window.S = 1; window.T = 1;

        window.javaBridge.debugStart(1, 1, 1);
        const frStr = window.javaBridge.debugStep();
        const fr = JSON.parse(frStr);

        // First step should execute turnLeft (from next connection), NOT turnRight from ELSE branch!
        assert.strictEqual(fr.t, 0, 'Pegman should turn left to NORTH (0), not right to SOUTH (2)');
    });

    it('recomputes debug trace dynamically when another block is added during stepping', async () => {
        let xml = `<xml xmlns="https://developers.google.com/blockly/xml">
          <block type="maze_moveForward" id="b1"></block>
        </xml>`;

        window.BlocklyInterface = {
            getCode: () => xml,
            getWorkspace: () => null
        };

        // 3x3 map: (0,0) START, (1,0) PATH, (1,1) PATH, facing EAST (1)
        window.X = [[2, 1, 0], [0, 1, 0], [0, 0, 0]];
        window.nd = { x: 0, y: 0 };
        window.Q = 0; window.S = 0; window.T = 1;

        window.javaBridge.debugStart(0, 0, 1);
        
        // Step 1: Pegman moves forward to (1, 0)
        let frStr = window.javaBridge.debugStep();
        let fr = JSON.parse(frStr);
        assert.strictEqual(fr.q, 1);
        assert.strictEqual(fr.s, 0);
        assert.strictEqual(fr.t, 1);

        // Now user adds a second block: maze_turn turnRight
        xml = `<xml xmlns="https://developers.google.com/blockly/xml">
          <block type="maze_moveForward" id="b1">
            <next>
              <block type="maze_turn" id="b2"><field name="DIR">turnRight</field></block>
            </next>
          </block>
        </xml>`;

        window.__updateWorkspaceAndTrace();

        // Immediate feedback path should now include the new prediction
        assert.ok(window.__injectNewPath.length >= 1);

        // Step 2: Pegman executes the newly added turnRight block!
        frStr = window.javaBridge.debugStep();
        fr = JSON.parse(frStr);
        assert.strictEqual(fr.q, 1, 'Pegman stays at x=1');
        assert.strictEqual(fr.s, 0, 'Pegman stays at y=0');
        assert.strictEqual(fr.t, 2, 'Pegman turned right to SOUTH (2)');
    });
});
