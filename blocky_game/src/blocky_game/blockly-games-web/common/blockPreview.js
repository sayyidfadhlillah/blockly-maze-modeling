/**
 * blockPreview.js
 * Renders visual Blockly Maze block stacks from workspace XML strings.
 * Lightweight, self-contained, does not load Blockly.
 * Compatible with browsers, standalone HTML exports, and Node.js test environments.
 */
(function(root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory();
    } else {
        root.BlockPreview = factory();
    }
})(typeof globalThis !== 'undefined' ? globalThis : this, function() {
    'use strict';

    var STYLES = '' +
        '.bp-workspace { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; font-size: 12px; line-height: 1.4; display: inline-block; min-width: 180px; max-width: 100%; padding: 8px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; box-sizing: border-box; text-align: left; }' +
        '.bp-stack { display: flex; flex-direction: column; gap: 3px; }' +
        '.bp-block { margin: 1px 0; border-radius: 5px; color: #ffffff; font-weight: 600; box-shadow: 0 1px 2px rgba(0,0,0,0.18); overflow: hidden; }' +
        '.bp-header { padding: 5px 10px; display: flex; align-items: center; gap: 6px; }' +
        '.bp-icon { font-size: 14px; }' +
        '.bp-statement-wrapper { background: rgba(0,0,0,0.08); border-left: 8px solid rgba(0,0,0,0.25); margin-left: 14px; padding: 4px 6px 4px 8px; min-height: 14px; }' +
        '.bp-statement-label { font-size: 10px; text-transform: uppercase; letter-spacing: 0.5px; opacity: 0.9; padding: 2px 8px; background: rgba(0,0,0,0.12); font-weight: 700; }' +
        '.bp-empty-stmt { color: rgba(255,255,255,0.7); font-style: italic; font-size: 11px; padding: 2px 4px; }' +
        '.bp-empty { color: #64748b; font-style: italic; font-size: 11px; padding: 6px; text-align: center; }' +
        '.bp-color-move { background: #3b82f6; }' +
        '.bp-color-turn { background: #2563eb; }' +
        '.bp-color-repeat { background: #10b981; }' +
        '.bp-color-if { background: #d97706; }' +
        '.bp-color-unknown { background: #64748b; }';

    var stylesInjected = false;
    function ensureStyles(doc) {
        if (stylesInjected) return;
        var d = doc || (typeof document !== 'undefined' ? document : null);
        if (!d || !d.head) return;
        try {
            var el = d.getElementById('blockPreviewStyles');
            if (!el) {
                el = d.createElement('style');
                el.id = 'blockPreviewStyles';
                el.textContent = STYLES;
                d.head.appendChild(el);
            }
            stylesInjected = true;
        } catch (e) {}
    }

    function parseXmlDocument(xmlStr) {
        if (!xmlStr || typeof xmlStr !== 'string' || !xmlStr.trim()) {
            return null;
        }
        var parser;
        if (typeof DOMParser !== 'undefined') {
            parser = new DOMParser();
        } else if (typeof window !== 'undefined' && window.DOMParser) {
            parser = new window.DOMParser();
        } else if (typeof global !== 'undefined' && global.DOMParser) {
            parser = new global.DOMParser();
        }
        if (parser) {
            try {
                var doc = parser.parseFromString(xmlStr, 'text/xml');
                if (doc && !doc.querySelector('parsererror')) {
                    return doc;
                }
            } catch (e) {}
        }
        return null;
    }

    function parseBlockElement(el) {
        if (!el || el.nodeType !== 1) return null;
        var type = el.getAttribute('type') || 'unknown';
        var id = el.getAttribute('id') || '';
        var fields = {};
        var statements = {};
        var nextBlock = null;

        var children = el.childNodes;
        for (var i = 0; i < children.length; i++) {
            var child = children[i];
            if (child.nodeType !== 1) continue;
            var tag = child.nodeName.toLowerCase();
            if (tag === 'field') {
                var fname = child.getAttribute('name') || 'value';
                fields[fname] = (child.textContent || '').trim();
            } else if (tag === 'statement') {
                var sname = (child.getAttribute('name') || 'DO').toUpperCase();
                var firstBlock = null;
                for (var j = 0; j < child.childNodes.length; j++) {
                    if (child.childNodes[j].nodeType === 1 && child.childNodes[j].nodeName.toLowerCase() === 'block') {
                        firstBlock = parseBlockChain(child.childNodes[j]);
                        break;
                    }
                }
                statements[sname] = firstBlock || [];
            } else if (tag === 'next') {
                for (var k = 0; k < child.childNodes.length; k++) {
                    if (child.childNodes[k].nodeType === 1 && child.childNodes[k].nodeName.toLowerCase() === 'block') {
                        nextBlock = child.childNodes[k];
                        break;
                    }
                }
            }
        }

        return {
            type: type,
            id: id,
            fields: fields,
            statements: statements,
            nextBlock: nextBlock
        };
    }

    function parseBlockChain(startEl) {
        var chain = [];
        var curr = startEl;
        while (curr) {
            var parsed = parseBlockElement(curr);
            if (!parsed) break;
            chain.push(parsed);
            curr = parsed.nextBlock;
        }
        return chain;
    }

    function parseWorkspaceXml(xmlStr) {
        var doc = parseXmlDocument(xmlStr);
        if (!doc) return [];
        var xmlRoot = doc.documentElement;
        if (!xmlRoot) return [];

        var topBlocks = [];
        var kids = xmlRoot.childNodes;
        for (var i = 0; i < kids.length; i++) {
            var k = kids[i];
            if (k.nodeType === 1 && k.nodeName.toLowerCase() === 'block') {
                var chain = parseBlockChain(k);
                if (chain && chain.length > 0) {
                    topBlocks.push(chain);
                }
            }
        }
        return topBlocks;
    }

    function formatCondition(dir) {
        if (!dir) return 'ahead';
        if (dir === 'isPathForward') return 'path ahead';
        if (dir === 'isPathLeft') return 'path to the left ↺';
        if (dir === 'isPathRight') return 'path to the right ↻';
        return dir;
    }

    function describeBlock(block) {
        var t = block.type;
        var f = block.fields || {};

        if (t === 'maze_moveForward') {
            return {
                title: 'move forward',
                icon: '➜',
                colorClass: 'bp-color-move',
                colorHex: '#3b82f6',
                hasDo: false,
                hasElse: false
            };
        } else if (t === 'maze_turn') {
            var dir = f.DIR || 'turnLeft';
            var isRight = (dir === 'turnRight');
            return {
                title: isRight ? 'turn right ↻' : 'turn left ↺',
                icon: isRight ? '↷' : '↶',
                colorClass: 'bp-color-turn',
                colorHex: '#2563eb',
                hasDo: false,
                hasElse: false
            };
        } else if (t === 'maze_forever') {
            return {
                title: 'repeat until 🏁',
                icon: '🔁',
                colorClass: 'bp-color-repeat',
                colorHex: '#10b981',
                hasDo: true,
                hasElse: false
            };
        } else if (t === 'controls_repeat') {
            var times = f.TIMES || '5';
            return {
                title: 'repeat ' + times + ' times',
                icon: '🔁',
                colorClass: 'bp-color-repeat',
                colorHex: '#059669',
                hasDo: true,
                hasElse: false
            };
        } else if (t === 'maze_if') {
            var cond = formatCondition(f.DIR);
            return {
                title: 'if ' + cond + ' do',
                icon: '❓',
                colorClass: 'bp-color-if',
                colorHex: '#d97706',
                hasDo: true,
                hasElse: false
            };
        } else if (t === 'maze_ifElse') {
            var condElse = formatCondition(f.DIR);
            return {
                title: 'if ' + condElse + ' do',
                icon: '❓',
                colorClass: 'bp-color-if',
                colorHex: '#ea580c',
                hasDo: true,
                hasElse: true
            };
        }

        return {
            title: t.replace(/^maze_/, '').replace(/^controls_/, ''),
            icon: '◻',
            colorClass: 'bp-color-unknown',
            colorHex: '#64748b',
            hasDo: (block.statements && !!block.statements.DO),
            hasElse: (block.statements && !!block.statements.ELSE)
        };
    }

    function renderChainToHtml(chain) {
        if (!chain || chain.length === 0) return '';
        var html = '<div class="bp-stack">';
        for (var i = 0; i < chain.length; i++) {
            var b = chain[i];
            var info = describeBlock(b);
            html += '<div class="bp-block ' + info.colorClass + '" style="background:' + info.colorHex + ';">';
            html += '<div class="bp-header"><span class="bp-icon">' + info.icon + '</span><span>' + escapeHtml(info.title) + '</span></div>';

            if (info.hasDo || (b.statements && b.statements.DO)) {
                var doBlocks = (b.statements && b.statements.DO) || [];
                html += '<div class="bp-statement-label">do</div>';
                html += '<div class="bp-statement-wrapper">';
                if (doBlocks.length > 0) {
                    html += renderChainToHtml(doBlocks);
                } else {
                    html += '<div class="bp-empty-stmt">(empty)</div>';
                }
                html += '</div>';
            }

            if (info.hasElse || (b.statements && b.statements.ELSE)) {
                var elseBlocks = (b.statements && b.statements.ELSE) || [];
                html += '<div class="bp-statement-label">else</div>';
                html += '<div class="bp-statement-wrapper">';
                if (elseBlocks.length > 0) {
                    html += renderChainToHtml(elseBlocks);
                } else {
                    html += '<div class="bp-empty-stmt">(empty)</div>';
                }
                html += '</div>';
            }

            html += '</div>';
        }
        html += '</div>';
        return html;
    }

    function escapeHtml(str) {
        if (!str) return '';
        return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function renderToHtml(xmlStr) {
        var topChains = parseWorkspaceXml(xmlStr);
        if (!topChains || topChains.length === 0) {
            return '<div class="bp-workspace"><div class="bp-empty">(Empty workspace)</div></div>';
        }
        var html = '<div class="bp-workspace">';
        for (var i = 0; i < topChains.length; i++) {
            html += renderChainToHtml(topChains[i]);
        }
        html += '</div>';
        return html;
    }

    function render(xmlStr, containerEl) {
        if (!containerEl) return null;
        var doc = containerEl.ownerDocument || document;
        ensureStyles(doc);
        var html = renderToHtml(xmlStr);
        containerEl.innerHTML = html;
        return containerEl;
    }

    function renderAll(rootEl) {
        var base = rootEl || (typeof document !== 'undefined' ? document : null);
        if (!base) return;
        var targets = base.querySelectorAll ? base.querySelectorAll('.block-preview[data-xml]') : [];
        for (var i = 0; i < targets.length; i++) {
            var el = targets[i];
            var xml = el.getAttribute('data-xml');
            render(xml, el);
        }
    }

    return {
        parse: parseWorkspaceXml,
        renderToHtml: renderToHtml,
        render: render,
        renderAll: renderAll,
        getStyles: function() { return STYLES; }
    };
});
