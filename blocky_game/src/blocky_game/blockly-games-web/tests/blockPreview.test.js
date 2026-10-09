const { describe, it, beforeEach } = require('node:test');
const assert = require('node:assert');
const { JSDOM } = require('jsdom');
const path = require('node:path');
const fs = require('node:fs');

describe('blockPreview.js Test Suite', () => {
    let dom;
    let window;
    let BlockPreview;

    beforeEach(() => {
        dom = new JSDOM('<!DOCTYPE html><html><body><div id="container"></div></body></html>', {
            runScripts: 'dangerously'
        });
        window = dom.window;
        global.window = window;
        global.document = window.document;
        global.DOMParser = window.DOMParser;

        const script = fs.readFileSync(path.join(__dirname, '../common/blockPreview.js'), 'utf8');
        window.eval(script);
        BlockPreview = window.BlockPreview;
    });

    it('exports BlockPreview API to window', () => {
        assert.ok(BlockPreview, 'BlockPreview should exist on window');
        assert.strictEqual(typeof BlockPreview.parse, 'function');
        assert.strictEqual(typeof BlockPreview.renderToHtml, 'function');
        assert.strictEqual(typeof BlockPreview.render, 'function');
        assert.strictEqual(typeof BlockPreview.renderAll, 'function');
        assert.strictEqual(typeof BlockPreview.getStyles, 'function');
    });

    it('parses linear block sequence with next connections', () => {
        const xml = [
            '<xml>',
            '  <block type="maze_moveForward">',
            '    <next>',
            '      <block type="maze_turn">',
            '        <field name="DIR">turnLeft</field>',
            '        <next>',
            '          <block type="maze_moveForward"></block>',
            '        </next>',
            '      </block>',
            '    </next>',
            '  </block>',
            '</xml>'
        ].join('\n');

        const topChains = BlockPreview.parse(xml);
        assert.strictEqual(topChains.length, 1);
        const chain = topChains[0];
        assert.strictEqual(chain.length, 3);
        assert.strictEqual(chain[0].type, 'maze_moveForward');
        assert.strictEqual(chain[1].type, 'maze_turn');
        assert.strictEqual(chain[1].fields.DIR, 'turnLeft');
        assert.strictEqual(chain[2].type, 'maze_moveForward');
    });

    it('parses nested repeat until block with DO statement', () => {
        const xml = [
            '<xml>',
            '  <block type="maze_forever">',
            '    <statement name="DO">',
            '      <block type="maze_moveForward"></block>',
            '    </statement>',
            '  </block>',
            '</xml>'
        ].join('\n');

        const topChains = BlockPreview.parse(xml);
        assert.strictEqual(topChains.length, 1);
        const chain = topChains[0];
        assert.strictEqual(chain.length, 1);
        assert.strictEqual(chain[0].type, 'maze_forever');
        assert.ok(chain[0].statements.DO);
        assert.strictEqual(chain[0].statements.DO.length, 1);
        assert.strictEqual(chain[0].statements.DO[0].type, 'maze_moveForward');
    });

    it('parses if and if-else conditional branches correctly', () => {
        const xml = [
            '<xml>',
            '  <block type="maze_ifElse">',
            '    <field name="DIR">isPathLeft</field>',
            '    <statement name="DO">',
            '      <block type="maze_turn"><field name="DIR">turnLeft</field></block>',
            '    </statement>',
            '    <statement name="ELSE">',
            '      <block type="maze_moveForward"></block>',
            '    </statement>',
            '  </block>',
            '</xml>'
        ].join('\n');

        const topChains = BlockPreview.parse(xml);
        assert.strictEqual(topChains.length, 1);
        const chain = topChains[0];
        assert.strictEqual(chain.length, 1);
        assert.strictEqual(chain[0].type, 'maze_ifElse');
        assert.strictEqual(chain[0].fields.DIR, 'isPathLeft');
        assert.strictEqual(chain[0].statements.DO.length, 1);
        assert.strictEqual(chain[0].statements.DO[0].type, 'maze_turn');
        assert.strictEqual(chain[0].statements.ELSE.length, 1);
        assert.strictEqual(chain[0].statements.ELSE[0].type, 'maze_moveForward');
    });

    it('renders color-coded HTML structure with correct classes', () => {
        const xml = '<xml><block type="maze_moveForward"></block></xml>';
        const html = BlockPreview.renderToHtml(xml);
        assert.ok(html.includes('bp-stack'), 'Should contain bp-stack');
        assert.ok(html.includes('bp-color-move'), 'Move forward should have bp-color-move class');
        assert.ok(html.includes('move forward'), 'Should display label');
    });

    it('mounts into DOM via render() and batch renders with renderAll()', () => {
        const container = window.document.getElementById('container');
        container.innerHTML = [
            '<div class="block-preview" id="p1" data-xml=\'<xml><block type="maze_moveForward"></block></xml>\'></div>',
            '<div class="block-preview" id="p2" data-xml=\'<xml><block type="maze_turn"><field name="DIR">turnRight</field></block></xml>\'></div>'
        ].join('');

        BlockPreview.renderAll(container);

        const p1 = window.document.getElementById('p1');
        const p2 = window.document.getElementById('p2');
        assert.ok(p1.querySelector('.bp-block'), 'p1 should have rendered blocks');
        assert.ok(p1.textContent.includes('move forward'));
        assert.ok(p2.querySelector('.bp-block'), 'p2 should have rendered blocks');
        assert.ok(p2.textContent.includes('turn right'));
    });

    it('handles empty or malformed XML gracefully without throwing', () => {
        assert.strictEqual(BlockPreview.parse('').length, 0);
        assert.strictEqual(BlockPreview.parse(null).length, 0);
        assert.strictEqual(BlockPreview.parse('<malformed><><').length, 0);

        const htmlEmpty = BlockPreview.renderToHtml('');
        assert.ok(htmlEmpty.includes('bp-empty'));

        const htmlNull = BlockPreview.renderToHtml(null);
        assert.ok(htmlNull.includes('bp-empty'));
    });

    it('returns non-empty CSS styles via getStyles()', () => {
        const styles = BlockPreview.getStyles();
        assert.ok(styles && styles.length > 50, 'getStyles should return non-empty stylesheet');
        assert.ok(styles.includes('.bp-stack'));
        assert.ok(styles.includes('.bp-block'));
        assert.ok(styles.includes('.bp-color-repeat'));
    });
});
