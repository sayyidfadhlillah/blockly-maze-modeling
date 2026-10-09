(function() {
    if (window.javaBridge) {
        // JavaFX WebView already provided window.javaBridge natively
        return;
    }

    console.log('[webBridge] Initializing browser REST adapter for Blockly Maze...');

    var apiBase = '';
    var sessionId = null;
    try {
        if (window.localStorage) {
            sessionId = window.localStorage.getItem('blocky_session_id');
        }
    } catch(e) {}

    function getSyncEpoch() {
        var epoch = 1;
        try {
            if (window.sessionStorage) {
                var raw = window.sessionStorage.getItem('blocky_sync_epoch');
                if (raw) {
                    epoch = parseInt(raw, 10);
                    if (isNaN(epoch) || epoch < 1) epoch = 1;
                }
            }
        } catch(e) {}
        return epoch;
    }

    function incrementSyncEpoch() {
        var epoch = 1;
        try {
            if (window.sessionStorage) {
                var raw = window.sessionStorage.getItem('blocky_sync_epoch');
                if (raw) {
                    epoch = parseInt(raw, 10);
                    if (isNaN(epoch) || epoch < 0) epoch = 0;
                }
                epoch++;
                window.sessionStorage.setItem('blocky_sync_epoch', String(epoch));
            }
        } catch(e) {}
        return epoch;
    }

    // Bump epoch once per page load/initialization
    incrementSyncEpoch();

    function clearWorkspaceBlocks() {
        try {
            var ws = (window.BlocklyInterface && window.BlocklyInterface.getWorkspace && window.BlocklyInterface.getWorkspace()) ||
                     (window.Blockly && window.Blockly.getMainWorkspace && window.Blockly.getMainWorkspace());
            if (ws && typeof ws.clear === 'function') {
                ws.clear();
            }
        } catch(e) {}
    }

    function initSessionState(sid) {
        if (!sid) return;
        fetch(apiBase + '/api/session/state', {
            headers: { 'X-Session-ID': sid }
        })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data && data.status === 'ok') {
                console.log('[webBridge] Loaded session state:', data);

                var frontendK = (typeof window.K === 'number') ? window.K : 1;
                var isSameLevel = (data.levelId === undefined || data.levelId === frontendK);

                if (isSameLevel && data.grid && Array.isArray(data.grid) && data.grid.length > 0) {
                    window.X = data.grid;
                }

                var startObj = findStartFromGrid();
                var goalObj = findGoalFromGrid();

                var startX, startY;
                if (startObj) {
                    startX = startObj.x;
                    startY = startObj.y;
                } else if (isSameLevel && data.startPos && typeof data.startPos.x === 'number') {
                    startX = data.startPos.x;
                    startY = data.startPos.y;
                } else {
                    startX = 0;
                    startY = 0;
                }

                window.nd = { x: startX, y: startY };
                window.Q = startX;
                window.S = startY;

                if (goalObj) {
                    window.od = { x: goalObj.x, y: goalObj.y };
                } else if (isSameLevel && data.goalPos && typeof data.goalPos.x === 'number') {
                    window.od = { x: data.goalPos.x, y: data.goalPos.y };
                }

                var derivedT = inferStartT(startX, startY);

                if (isSameLevel && typeof data.startDirection === 'number' && data.grid && Array.isArray(data.grid) && data.grid.length > 0) {
                    var DX = [0, 1, 0, -1];
                    var DY = [-1, 0, 1, 0];
                    var grid = getActiveGrid();
                    var cDir = (data.startDirection % 4 + 4) % 4;
                    var nx = startX + DX[cDir];
                    var ny = startY + DY[cDir];
                    if (grid && grid[ny] && (grid[ny][nx] === 1 || grid[ny][nx] === 3)) {
                        window.T = data.startDirection;
                    } else {
                        window.T = derivedT;
                    }
                } else {
                    window.T = derivedT;
                }

                window.modelStartT = window.T;
                window.__modelStartT = window.T;
                window.__stableStartT = window.T;

                if (isSameLevel && data.maxBlocks !== undefined) window.Od = data.maxBlocks;

                if (typeof data.momotRunning === 'boolean') {
                    momotIsRunning = data.momotRunning;
                    window.__momotIsRunning = data.momotRunning;
                }
                window.__injectNewPath = data.newPath || [];
                window.__injectPastPath = data.pastPath || [];

                if (isSameLevel) {
                    window.__injectNewPath = data.newPath || [];
                    window.__injectPastPath = data.pastPath || [];

                    var isCleared = false;
                    try {
                        if (window.sessionStorage) {
                            isCleared = !!window.sessionStorage.getItem('blocky_cleared_' + frontendK);
                        }
                    } catch (eC) {}

                    if (isCleared) {
                        console.log('[webBridge] Level ' + frontendK + ' is marked as intentionally cleared; sending empty snapshot.');
                        try {
                            if (window.localStorage) {
                                window.localStorage.removeItem('maze' + frontendK);
                            }
                        } catch (eL) {}
                        clearWorkspaceBlocks();
                        bridge.syncModel('', true);
                    } else if (data.xml) {
                        applyXmlToWorkspace(data.xml);
                    }
                } else {
                    console.log('[webBridge] Level switch detected (backend levelId=' + data.levelId + ', frontend levelId=' + frontendK + '). Syncing new level state...');

                    window.__lastSyncedXml = '';
                    window.__lastObservedXml = '';

                    resetMomotClientState();

                    localDbg.startX = startX;
                    localDbg.startY = startY;
                    localDbg.startT = window.T;
                    localDbg.index = 0;
                    localDbg.paused = true;
                    localDbg.active = false;

                    var traceRes = computeLocalDebugTrace(startX, startY, window.T);
                    localDbg.states = traceRes.states || [];
                    window.__injectNewPath = compressRemainingPositions(localDbg.states, 0);
                    window.__injectPastPath = [];

                    syncCurrentLevelStateToBackend();
                }

                // Ensure Pegman and Goal Pin are rendered at window.nd and window.od
                if (typeof window.$d === 'function') {
                    try { window.$d(false); } catch(eD) {}
                }

                if (typeof window.__ifRender === 'function') {
                    window.__ifRender();
                }
                if (typeof window.__lvlLoadingHide === 'function') {
                    window.__lvlLoadingHide();
                }
            }
        })
        .catch(function(err) {
            console.error('[webBridge] Failed to fetch session state:', err);
        });
    }

    var sessionPendingCallbacks = [];
    var isFetchingSession = false;

    function getSessionId(callback) {
        if (sessionId) {
            if (callback) callback(sessionId);
            return;
        }
        if (callback) sessionPendingCallbacks.push(callback);
        if (isFetchingSession) return;
        isFetchingSession = true;

        fetch(apiBase + '/api/session/new', { method: 'POST' })
            .then(function(res) { return res.json(); })
            .then(function(data) {
                if (data && data.sessionId) {
                    sessionId = data.sessionId;
                    localStorage.setItem('blocky_session_id', sessionId);
                    console.log('[webBridge] Created session:', sessionId);
                    initSessionState(sessionId);
                }
                isFetchingSession = false;
                var cbs = sessionPendingCallbacks.slice();
                sessionPendingCallbacks = [];
                cbs.forEach(function(cb) { try { cb(sessionId); } catch(e) {} });
            })
            .catch(function(err) {
                console.error('[webBridge] Failed to create session:', err);
                isFetchingSession = false;
                var cbs = sessionPendingCallbacks.slice();
                sessionPendingCallbacks = [];
                cbs.forEach(function(cb) { try { cb(null); } catch(e) {} });
            });
    }

    if (sessionId) {
        initSessionState(sessionId);
    } else {
        getSessionId();
    }

    var cachedSolutions = [];
    var momotIsRunning = false;
    window.__momotIsRunning = false;

    // ==========================================
    // Client-Side Local Debugger Engine
    // ==========================================
    var localDbg = {
        active: false,
        paused: true,
        index: 0,
        startX: 0,
        startY: 0,
        startT: 1,
        states: [],
        pastPrefixPath: [],
        note: ''
    };

    function compressPositions(states, uptoIndex) {
        if (!states || !states.length) return [];
        var pts = [];
        var lastX = null, lastY = null;
        var end = Math.min(uptoIndex, states.length - 1);
        for (var i = 0; i <= end; i++) {
            var st = states[i];
            if (!st) continue;
            if (st.q === lastX && st.s === lastY) continue;
            pts.push([st.q, st.s]);
            lastX = st.q;
            lastY = st.s;
        }
        return pts;
    }

    function compressRemainingPositions(states, fromIndex) {
        if (!states || !states.length) return [];
        var pts = [];
        var lastX = null, lastY = null;
        var start = Math.max(0, fromIndex);
        for (var i = start; i < states.length; i++) {
            var st = states[i];
            if (!st) continue;
            if (st.q === lastX && st.s === lastY) continue;
            pts.push([st.q, st.s]);
            lastX = st.q;
            lastY = st.s;
        }
        return pts;
    }

    function getActiveGrid() {
        try {
            var x = window.X;
            if (!x || !Array.isArray(x)) return null;
            if (Array.isArray(x[0]) && (x[0].length === 0 || typeof x[0][0] === 'number')) {
                return x;
            }
            var k = (typeof window.K === 'number') ? window.K : 1;
            if (x[k] && Array.isArray(x[k]) && (x[k].length === 0 || Array.isArray(x[k][0]))) {
                return x[k];
            }
            for (var i = 0; i < x.length; i++) {
                if (x[i] && Array.isArray(x[i]) && Array.isArray(x[i][0]) && (x[i][0].length === 0 || typeof x[i][0][0] === 'number')) {
                    return x[i];
                }
            }
        } catch(e) {}
        return null;
    }
    window.__getActiveGrid = getActiveGrid;

    function findStartFromGrid() {
        try {
            var grid = getActiveGrid();
            if (grid && Array.isArray(grid)) {
                for (var y = 0; y < grid.length; y++) {
                    if (Array.isArray(grid[y])) {
                        for (var x = 0; x < grid[y].length; x++) {
                            if (grid[y][x] === 2) {
                                return { x: x, y: y };
                            }
                        }
                    }
                }
            }
        } catch(e) {}
        return null;
    }

    function findGoalFromGrid() {
        try {
            var grid = getActiveGrid();
            if (grid && Array.isArray(grid)) {
                for (var y = 0; y < grid.length; y++) {
                    if (Array.isArray(grid[y])) {
                        for (var x = 0; x < grid[y].length; x++) {
                            if (grid[y][x] === 3) {
                                return { x: x, y: y };
                            }
                        }
                    }
                }
            }
        } catch(e) {}
        return null;
    }

    function inferStartT(startX, startY) {
        try {
            var grid = getActiveGrid();
            if (grid && Array.isArray(grid) && typeof startX === 'number' && typeof startY === 'number') {
                var rows = grid.length;
                var cols = (grid[0] && Array.isArray(grid[0])) ? grid[0].length : 0;
                // Check EAST (x+1, y) -> 1
                if (startX + 1 < cols && grid[startY] && (grid[startY][startX + 1] === 1 || grid[startY][startX + 1] === 3)) return 1;
                // Check NORTH (x, y-1) -> 0
                if (startY - 1 >= 0 && grid[startY - 1] && (grid[startY - 1][startX] === 1 || grid[startY - 1][startX] === 3)) return 0;
                // Check SOUTH (x, y+1) -> 2
                if (startY + 1 < rows && grid[startY + 1] && (grid[startY + 1][startX] === 1 || grid[startY + 1][startX] === 3)) return 2;
                // Check WEST (x-1, y) -> 3
                if (startX - 1 >= 0 && grid[startY] && (grid[startY][startX - 1] === 1 || grid[startY][startX - 1] === 3)) return 3;
            }
        } catch(e) {}
        return 0;
    }

    function getStartT(t) {
        if (typeof t === 'number' && !isNaN(t)) return t;

        var s = findStartFromGrid();
        if (s) {
            var inferred = inferStartT(s.x, s.y);
            var candidateT = (typeof window.__modelStartT === 'number' && !isNaN(window.__modelStartT)) ? window.__modelStartT :
                             ((typeof window.modelStartT === 'number' && !isNaN(window.modelStartT)) ? window.modelStartT :
                             ((typeof window.__stableStartT === 'number' && !isNaN(window.__stableStartT)) ? window.__stableStartT :
                             ((typeof window.T === 'number' && !isNaN(window.T)) ? window.T : undefined)));

            if (typeof candidateT === 'number') {
                var DX = [0, 1, 0, -1];
                var DY = [-1, 0, 1, 0];
                var grid = getActiveGrid();
                var cDir = (candidateT % 4 + 4) % 4;
                var nx = s.x + DX[cDir];
                var ny = s.y + DY[cDir];
                if (grid && grid[ny] && (grid[ny][nx] === 1 || grid[ny][nx] === 3)) {
                    return candidateT;
                }
            }
            return inferred;
        }

        if (typeof window.__modelStartT === 'number' && !isNaN(window.__modelStartT)) return window.__modelStartT;
        if (typeof window.modelStartT === 'number' && !isNaN(window.modelStartT)) return window.modelStartT;
        if (typeof window.__stableStartT === 'number' && !isNaN(window.__stableStartT)) return window.__stableStartT;
        if (typeof window.T === 'number' && !isNaN(window.T)) return window.T;
        return 0;
    }

    function getStartX(q) {
        var s = findStartFromGrid();
        if (s) return s.x;
        if (typeof q === 'number' && !isNaN(q)) return q;
        if (window.nd && typeof window.nd.x === 'number' && !isNaN(window.nd.x)) return window.nd.x;
        if (typeof window.Q === 'number' && !isNaN(window.Q)) return window.Q;
        return 0;
    }

    function getStartY(s) {
        var st = findStartFromGrid();
        if (st) return st.y;
        if (typeof s === 'number' && !isNaN(s)) return s;
        if (window.nd && typeof window.nd.y === 'number' && !isNaN(window.nd.y)) return window.nd.y;
        if (typeof window.S === 'number' && !isNaN(window.S)) return window.S;
        return 0;
    }

    function parseTopBlocksFromXmlDom(xmlDom) {
        var topBlocks = [];
        if (!xmlDom) return topBlocks;
        var children = xmlDom.childNodes || [];
        for (var i = 0; i < children.length; i++) {
            var node = children[i];
            if (node.nodeType === 1 && node.tagName && node.tagName.toLowerCase() === 'block') {
                topBlocks.push(wrapXmlBlockNode(node));
            }
        }
        return topBlocks;
    }

    function wrapXmlBlockNode(node) {
        if (!node || node.nodeType !== 1) return null;
        return {
            type: node.getAttribute('type') || '',
            id: node.getAttribute('id') || '',
            _xmlNode: node,
            getFieldValue: function(fieldName) {
                var kids = node.childNodes || [];
                for (var i = 0; i < kids.length; i++) {
                    var k = kids[i];
                    if (k.nodeType === 1 && (k.tagName.toLowerCase() === 'field' || k.tagName.toLowerCase() === 'title')) {
                        if (k.getAttribute('name') === fieldName) {
                            return k.textContent || '';
                        }
                    }
                }
                return '';
            },
            getNextBlock: function() {
                var kids = node.childNodes || [];
                for (var i = 0; i < kids.length; i++) {
                    var k = kids[i];
                    if (k.nodeType === 1 && k.tagName.toLowerCase() === 'next') {
                        var subKids = k.childNodes || [];
                        for (var j = 0; j < subKids.length; j++) {
                            if (subKids[j].nodeType === 1 && subKids[j].tagName.toLowerCase() === 'block') {
                                return wrapXmlBlockNode(subKids[j]);
                            }
                        }
                    }
                }
                return null;
            },
            getInputTargetBlock: function(inputName) {
                var kids = node.childNodes || [];
                for (var i = 0; i < kids.length; i++) {
                    var k = kids[i];
                    if (k.nodeType === 1 && (k.tagName.toLowerCase() === 'statement' || k.tagName.toLowerCase() === 'value')) {
                        var name = k.getAttribute('name') || '';
                        if (name === inputName || (inputName === 'DO' && (name === 'DO0' || name.indexOf('DO') === 0))) {
                            var subKids = k.childNodes || [];
                            for (var j = 0; j < subKids.length; j++) {
                                if (subKids[j].nodeType === 1 && subKids[j].tagName.toLowerCase() === 'block') {
                                    return wrapXmlBlockNode(subKids[j]);
                                }
                            }
                        }
                    }
                }
                return null;
            },
            getChildren: function() {
                var kids = [];
                var doB = this.getInputTargetBlock('DO') || this.getInputTargetBlock('DO0');
                if (doB) kids.push(doB);
                var elseB = this.getInputTargetBlock('ELSE');
                if (elseB) kids.push(elseB);
                return kids;
            }
        };
    }

    function getTopBlocksFromWorkspace(ws) {
        // 1. Try parsing XML DOM from BlocklyInterface or Blockly.Xml or __lastSyncedXml
        try {
            if (window.BlocklyInterface && typeof window.BlocklyInterface.getCode === 'function') {
                var xmlStr = window.BlocklyInterface.getCode();
                if (xmlStr && typeof xmlStr === 'string' && xmlStr.indexOf('<xml') !== -1) {
                    var parser = new DOMParser();
                    var doc = parser.parseFromString(xmlStr, 'text/xml');
                    if (doc && doc.documentElement) {
                        var xmlBlocks = parseTopBlocksFromXmlDom(doc.documentElement);
                        if (xmlBlocks && xmlBlocks.length) return xmlBlocks;
                    }
                }
            }
        } catch(e) {}

        try {
            if (window.Blockly && window.Blockly.Xml && ws) {
                var dom = window.Blockly.Xml.workspaceToDom(ws);
                if (dom) {
                    var xmlBlocks2 = parseTopBlocksFromXmlDom(dom);
                    if (xmlBlocks2 && xmlBlocks2.length) return xmlBlocks2;
                }
            }
        } catch(e) {}

        if (window.__lastSyncedXml) {
            try {
                var parser2 = new DOMParser();
                var doc2 = parser2.parseFromString(window.__lastSyncedXml, 'text/xml');
                if (doc2 && doc2.documentElement) {
                    var xmlBlocks3 = parseTopBlocksFromXmlDom(doc2.documentElement);
                    if (xmlBlocks3 && xmlBlocks3.length) return xmlBlocks3;
                }
            } catch(e) {}
        }

        // 2. Fallback to uncompressed JS workspace objects (e.g. test environments)
        if (ws) {
            if (typeof ws.getTopBlocks === 'function') {
                var tb = ws.getTopBlocks(true);
                if (tb && tb.length) return tb;
            }
            if (ws.topBlocks_ && Array.isArray(ws.topBlocks_) && ws.topBlocks_.length) {
                return ws.topBlocks_;
            }
            if (ws.Ui && Array.isArray(ws.Ui) && ws.Ui.length) {
                return ws.Ui;
            }
            if (window.Blockly && typeof window.Blockly.getMainWorkspace === 'function') {
                var mws = window.Blockly.getMainWorkspace();
                if (mws && mws !== ws) {
                    return getTopBlocksFromWorkspace(mws);
                }
            }
        }
        return [];
    }

    function safeGetNextBlock(block) {
        if (!block) return null;
        if (typeof block.getNextBlock === 'function') {
            var nb = block.getNextBlock();
            if (nb) return nb;
        }
        if (block.nextConnection && typeof block.nextConnection.targetBlock === 'function') {
            var nb2 = block.nextConnection.targetBlock();
            if (nb2) return nb2;
        }
        if (block._xmlNode && typeof block.getNextBlock === 'function') {
            return block.getNextBlock();
        }
        return null;
    }

    function safeGetInputTargetBlock(block, inputName) {
        if (!block) return null;
        if (typeof block.getInputTargetBlock === 'function') {
            var ib = block.getInputTargetBlock(inputName);
            if (ib) return ib;
        }
        if (typeof block.getInput === 'function') {
            var inp = block.getInput(inputName);
            if (inp && inp.connection && typeof inp.connection.targetBlock === 'function') {
                var ib2 = inp.connection.targetBlock();
                if (ib2) return ib2;
            }
        }
        if (block._xmlNode && typeof block.getInputTargetBlock === 'function') {
            return block.getInputTargetBlock(inputName);
        }
        return null;
    }

    function safeGetFieldValue(block, fieldName) {
        if (!block) return '';
        if (typeof block.getFieldValue === 'function') {
            var val = block.getFieldValue(fieldName);
            if (val !== null && val !== undefined) return String(val);
        }
        if (block._xmlNode && typeof block.getFieldValue === 'function') {
            return block.getFieldValue(fieldName);
        }
        return '';
    }

    function computeLocalDebugTrace(startX, startY, startT) {
        var grid = getActiveGrid() || [[2, 1, 3]];
        var rows = grid.length;
        var cols = (grid[0] && Array.isArray(grid[0])) ? grid[0].length : 0;

        var dirIdx = (typeof startT === 'number') ? ((startT % 4 + 4) % 4) : 0;
        var DX = [0, 1, 0, -1];
        var DY = [-1, 0, 1, 0];
        var DIR_NAMES = ['NORTH', 'EAST', 'SOUTH', 'WEST'];

        var curX = (typeof startX === 'number' && startX >= 0) ? startX : 0;
        var curY = (typeof startY === 'number' && startY >= 0) ? startY : 0;
        var curDir = dirIdx;

        var states = [];

        states.push({
            q: curX,
            s: curY,
            t: curDir,
            blockId: '',
            result: 'RUNNING',
            logLine: 'Start: (' + curX + ',' + curY + ') dir=' + DIR_NAMES[curDir]
        });

        if (grid[curY] && grid[curY][curX] === 3) {
            states[0].result = 'WON';
            return { states: states };
        }

        var ws = (window.BlocklyInterface && window.BlocklyInterface.getWorkspace && window.BlocklyInterface.getWorkspace()) ||
                 (window.Blockly && window.Blockly.getMainWorkspace && window.Blockly.getMainWorkspace());

        var stepCount = 0;
        var maxSteps = 1000;
        var finished = false;

        function isPath(dirOffset) {
            var d = (curDir + dirOffset + 4) % 4;
            var nx = curX + DX[d];
            var ny = curY + DY[d];
            if (ny < 0 || ny >= rows || nx < 0 || nx >= cols) return false;
            var cell = grid[ny][nx];
            return cell === 1 || cell === 2 || cell === 3;
        }

        function doMoveForward(blockId) {
            if (finished) return;
            stepCount++;
            var nx = curX + DX[curDir];
            var ny = curY + DY[curDir];
            if (ny < 0 || ny >= rows || nx < 0 || nx >= cols || grid[ny][nx] === 0) {
                finished = true;
                states.push({
                    q: curX,
                    s: curY,
                    t: curDir,
                    blockId: blockId || '',
                    result: 'CRASHED',
                    logLine: 'CRASHED at (' + curX + ',' + curY + ')'
                });
                return;
            }
            curX = nx;
            curY = ny;
            var isGoal = (grid[curY] && grid[curY][curX] === 3);
            var res = isGoal ? 'WON' : 'RUNNING';
            states.push({
                q: curX,
                s: curY,
                t: curDir,
                blockId: blockId || '',
                result: res,
                logLine: 'Move forward -> (' + curX + ',' + curY + ')'
            });
            if (isGoal) finished = true;
        }

        function doTurnLeft(blockId) {
            if (finished) return;
            stepCount++;
            curDir = (curDir + 3) % 4;
            states.push({
                q: curX,
                s: curY,
                t: curDir,
                blockId: blockId || '',
                result: 'RUNNING',
                logLine: 'Turn left -> dir=' + DIR_NAMES[curDir]
            });
        }

        function doTurnRight(blockId) {
            if (finished) return;
            stepCount++;
            curDir = (curDir + 1) % 4;
            states.push({
                q: curX,
                s: curY,
                t: curDir,
                blockId: blockId || '',
                result: 'RUNNING',
                logLine: 'Turn right -> dir=' + DIR_NAMES[curDir]
            });
        }

        function evalCondition(condKind) {
            var k = (condKind || '').toLowerCase();
            if (k.indexOf('forward') >= 0 || k === 'ispathforward') return isPath(0);
            if (k.indexOf('right') >= 0 || k === 'ispathright') return isPath(1);
            if (k.indexOf('backward') >= 0 || k === 'ispathbackward') return isPath(2);
            if (k.indexOf('left') >= 0 || k === 'ispathleft') return isPath(3);
            return isPath(0);
        }

        function executeBlock(block) {
            if (!block || finished || stepCount >= maxSteps) return;
            var type = block.type || '';
            var id = block.id || '';

            if (type === 'maze_moveForward') {
                doMoveForward(id);
            } else if (type === 'maze_turn') {
                var dir = safeGetFieldValue(block, 'DIR');
                if (!dir) dir = 'turnLeft';
                if (dir === 'turnLeft' || dir === 'left' || dir === 'isPathLeft') doTurnLeft(id);
                else doTurnRight(id);
            } else if (type === 'controls_repeat' || type === 'maze_forever') {
                var isForever = (type === 'maze_forever');
                var timesVal = safeGetFieldValue(block, 'TIMES');
                var repeatLimit = isForever ? 100 : (parseInt(timesVal, 10) || 10);
                var rep = 0;
                while (!finished && rep < repeatLimit && stepCount < maxSteps) {
                    var bodyBlock = safeGetInputTargetBlock(block, 'DO');
                    if (!bodyBlock && typeof block.getChildren === 'function') {
                        var kids = block.getChildren(true);
                        bodyBlock = kids && kids.length ? kids[0] : null;
                    }
                    if (!bodyBlock) break;
                    executeBlockList(bodyBlock);
                    rep++;
                }
            } else if (type === 'controls_if' || type === 'maze_if' || type === 'maze_ifElse') {
                var condKind = safeGetFieldValue(block, 'DIR');
                if (!condKind) condKind = 'isPathForward';
                var condVal = evalCondition(condKind);
                var doBlock = safeGetInputTargetBlock(block, 'DO') || safeGetInputTargetBlock(block, 'DO0');
                var elseBlock = safeGetInputTargetBlock(block, 'ELSE');
                if (condVal) {
                    if (doBlock) executeBlockList(doBlock);
                } else {
                    if (elseBlock) executeBlockList(elseBlock);
                }
            }
        }

        function executeBlockList(startBlock) {
            var b = startBlock;
            while (b && !finished && stepCount < maxSteps) {
                executeBlock(b);
                b = safeGetNextBlock(b);
            }
        }

        var topBlocks = getTopBlocksFromWorkspace(ws);
        for (var i = 0; i < topBlocks.length && !finished; i++) {
            executeBlockList(topBlocks[i]);
        }

        if (!finished && stepCount >= maxSteps) {
            states.push({
                q: curX,
                s: curY,
                t: curDir,
                blockId: '',
                result: 'STEP_LIMIT_EXCEEDED',
                logLine: 'Step limit exceeded'
            });
        }

        var newPreview = compressRemainingPositions(states, localDbg.active ? localDbg.index : 0);
        window.__injectNewPath = newPreview;

        return { states: states };
    }

    function buildDebugFrameJson() {
        var states = localDbg.states || [];
        var total = states.length;
        var idx = Math.max(0, Math.min(localDbg.index, total > 0 ? total - 1 : 0));
        var curr = (total > 0 && states[idx]) ? states[idx] : { q: localDbg.startX, s: localDbg.startY, t: localDbg.startT, blockId: '', result: 'RUNNING', logLine: '' };

        var prefix = compressPositions(states, idx);
        var newPreview = compressRemainingPositions(states, idx);

        return JSON.stringify({
            index: idx,
            total: total,
            q: curr.q,
            s: curr.s,
            t: curr.t,
            prefix: prefix,
            pastPrefix: localDbg.pastPrefixPath || [],
            newPreview: newPreview,
            common: 0,
            paused: localDbg.paused,
            dirty: false,
            blockId: curr.blockId || '',
            result: curr.result || 'RUNNING',
            logLine: curr.logLine || '',
            note: localDbg.note || ''
        });
    }

    function syncCurrentLevelStateToBackend(callback) {
        var activeGrid = getActiveGrid();
        var frontendK = (typeof window.K === 'number') ? window.K : 1;
        var currentXml = getCurrentWorkspaceXml();
        var mapJson = activeGrid ? JSON.stringify(activeGrid) : null;
        var metaJson = JSON.stringify({
            level: frontendK,
            maxBlocks: window.Od || 10,
            startDirection: window.T || 1
        });

        bridge.syncSnapshot(mapJson, metaJson, currentXml, false, callback);
    }
    window.__syncCurrentLevelStateToBackend = syncCurrentLevelStateToBackend;

    function getTimerSessionInfo() {
        var tsId = null;
        try {
            tsId = sessionStorage.getItem('blocky_level_timer_session_id');
            if (tsId) tsId = String(tsId).trim();
        } catch (e) {}

        var lvl = 1;
        try {
            var match = window.location.search.match(/[?&]level=([^&]+)/);
            var parsed = match ? parseInt(decodeURIComponent(match[1].replace(/\+/g, '%20')), 10) : 0;
            if (parsed >= 1 && parsed <= 10) {
                lvl = parsed;
            } else if (typeof window.K === 'number' && window.K >= 1 && window.K <= 10) {
                lvl = window.K;
            }
        } catch (e2) {}

        return { timerSessionId: tsId || '', level: lvl };
    }

    function attachTimerHeaders(headers) {
        var h = headers || {};
        var info = getTimerSessionInfo();
        if (info.timerSessionId) {
            h['X-Timer-Session-ID'] = info.timerSessionId;
        }
        h['X-Level-ID'] = String(info.level);
        return h;
    }

    var bridge = {
        logJS: function(msg) {
            console.log('[JS Log]', msg);
        },

        syncModel: function(xml, allowEmpty) {
            var hasBlocks = xml && xml.indexOf('<block') >= 0;
            if (!hasBlocks && !allowEmpty) return;
            window.__lastSyncedXml = xml || '';
            var epoch = getSyncEpoch();
            var info = getTimerSessionInfo();
            getSessionId(function(sid) {
                if (!sid) return;
                fetch(apiBase + '/api/session/sync', {
                    method: 'POST',
                    headers: attachTimerHeaders({ 'Content-Type': 'application/json', 'X-Session-ID': sid }),
                    body: JSON.stringify({
                        xml: xml || '',
                        allowEmpty: !!allowEmpty,
                        epoch: epoch,
                        timerSessionId: info.timerSessionId,
                        level: info.level
                    })
                })
                .then(function() {
                    // Update simulation trace and immediate feedback path overlay
                    return fetch(apiBase + '/api/simulation/run', {
                        method: 'POST',
                        headers: attachTimerHeaders({ 'Content-Type': 'application/json', 'X-Session-ID': sid }),
                        body: JSON.stringify({
                            timerSessionId: info.timerSessionId,
                            level: info.level
                        })
                    });
                })
                .then(function(r) { return r.json(); })
                .then(function(data) {
                    if (data && data.newPath) {
                        var frontendK = (typeof window.K === 'number') ? window.K : 1;
                        if (data.levelId === undefined || data.levelId === frontendK) {
                            window.__injectNewPath = data.newPath;
                            if (typeof window.__ifRender === 'function') {
                                window.__ifRender();
                            }
                        }
                    }
                })
                .catch(function(e) { console.error('[webBridge] syncModel error', e); });
            });
        },

        syncMap: function(mapJson) {
            var epoch = getSyncEpoch();
            getSessionId(function(sid) {
                if (!sid) return;
                fetch(apiBase + '/api/session/map', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', 'X-Session-ID': sid },
                    body: JSON.stringify({ map: mapJson, epoch: epoch })
                }).catch(function(e) { console.error('[webBridge] syncMap error', e); });
            });
        },

        syncLevelMeta: function(metaJson) {
            var epoch = getSyncEpoch();
            getSessionId(function(sid) {
                if (!sid) return;
                fetch(apiBase + '/api/session/meta', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', 'X-Session-ID': sid },
                    body: JSON.stringify({ meta: metaJson, epoch: epoch })
                }).catch(function(e) { console.error('[webBridge] syncLevelMeta error', e); });
            });
        },

        syncSnapshot: function(mapJson, metaJson, xml, allowEmpty, callback) {
            var epoch = getSyncEpoch();
            var info = getTimerSessionInfo();
            getSessionId(function(sid) {
                if (!sid) {
                    if (typeof callback === 'function') callback();
                    return;
                }
                fetch(apiBase + '/api/session/snapshot', {
                    method: 'POST',
                    headers: attachTimerHeaders({ 'Content-Type': 'application/json', 'X-Session-ID': sid }),
                    body: JSON.stringify({
                        epoch: epoch,
                        map: mapJson,
                        meta: metaJson,
                        xml: xml || '',
                        allowEmpty: !!allowEmpty,
                        timerSessionId: info.timerSessionId,
                        level: info.level
                    })
                })
                .then(function(r) { return r.json(); })
                .then(function() {
                    if (typeof callback === 'function') callback();
                })
                .catch(function(e) {
                    console.error('[webBridge] syncSnapshot error', e);
                    if (typeof callback === 'function') callback();
                });
            });
        },

        runSimulation: function() {
            var info = getTimerSessionInfo();
            getSessionId(function(sid) {
                if (!sid) return;
                fetch(apiBase + '/api/simulation/run', {
                    method: 'POST',
                    headers: attachTimerHeaders({ 'Content-Type': 'application/json', 'X-Session-ID': sid }),
                    body: JSON.stringify({
                        timerSessionId: info.timerSessionId,
                        level: info.level
                    })
                })
                .then(function(r) { return r.json(); })
                .then(function(data) {
                    if (!localDbg.active && data && data.logs && window.__execLogAppend) {
                        window.__execLogAppend(data.logs);
                    }
                    if (data && data.newPath) {
                        var frontendK = (typeof window.K === 'number') ? window.K : 1;
                        if (data.levelId === undefined || data.levelId === frontendK) {
                            window.__injectNewPath = data.newPath;
                            if (typeof window.__ifRender === 'function') {
                                window.__ifRender();
                            }
                        }
                    }
                })
                .catch(function(e) { console.error('[webBridge] runSimulation error', e); });
            });
        },

        runSimulationWithGen: function(gen) {
            this.runSimulation();
        },

        runMomotWithParams: function(seed, pop, eval, runs, solLen) {
            cachedSolutions = [];
            momotIsRunning = true;
            window.__momotIsRunning = true;
            if (typeof window.__momotClearSolutions === 'function') {
                window.__momotClearSolutions();
            }
            var popVal = parseInt(pop, 10) || 1;
            var evalVal = parseInt(eval, 10) || 0;
            var runsVal = parseInt(runs, 10) || 1;
            var totalGens = Math.max(1, Math.floor(evalVal / popVal));
            if (typeof window.__momotProgressStart === 'function') {
                window.__momotProgressStart(runsVal, totalGens);
            }
            if (typeof window.__momotSetStatus === 'function') {
                window.__momotSetStatus('Starting MoMoT...');
            }
            var info = getTimerSessionInfo();
            var activeGrid = getActiveGrid();
            var frontendK = (typeof window.K === 'number') ? window.K : info.level;
            var currentXml = getCurrentWorkspaceXml() || '';
            var epoch = getSyncEpoch();

            var mapJson = activeGrid ? JSON.stringify(activeGrid) : null;
            var metaJson = JSON.stringify({
                level: frontendK,
                maxBlocks: window.Od || 10,
                startDirection: window.T || 1
            });

            getSessionId(function(sid) {
                if (!sid) {
                    momotIsRunning = false;
                    window.__momotIsRunning = false;
                    if (typeof window.__momotProgressDone === 'function') {
                        window.__momotProgressDone();
                    }
                    if (typeof window.__momotSetStatus === 'function') {
                        window.__momotSetStatus('Run failed: No session available');
                    }
                    return;
                }
                fetch(apiBase + '/api/momot/run', {
                    method: 'POST',
                    headers: attachTimerHeaders({ 'Content-Type': 'application/json', 'X-Session-ID': sid }),
                    body: JSON.stringify({
                        seed: seed,
                        populationSize: pop,
                        maxEvaluations: eval,
                        nrRuns: runs,
                        solutionLength: solLen,
                        timerSessionId: info.timerSessionId,
                        level: info.level,
                        epoch: epoch,
                        map: mapJson,
                        meta: metaJson,
                        xml: currentXml
                    })
                }).then(function(r) {
                    if (r && typeof r.ok === 'boolean' && !r.ok) {
                        throw new Error('HTTP ' + r.status);
                    }
                    return r.json();
                })
                .then(function(data) {
                    console.log('[webBridge] MOMoT run started:', data);
                    if (data && data.status && data.status !== 'ok' && !data.running) {
                        throw new Error(data.message || data.status);
                    }
                    momotIsRunning = true;
                    window.__momotIsRunning = true;
                    startStatusPolling();
                })
                .catch(function(e) {
                    console.error('[webBridge] runMomot error', e);
                    momotIsRunning = false;
                    window.__momotIsRunning = false;
                    if (typeof window.__momotProgressDone === 'function') {
                        window.__momotProgressDone();
                    }
                    if (typeof window.__momotSetStatus === 'function') {
                        var msg = (e && e.message) ? e.message : String(e);
                        window.__momotSetStatus('Run failed: ' + msg);
                    }
                });
            });
        },

        stopMomotRun: function() {
            momotIsRunning = false;
            window.__momotIsRunning = false;
            return new Promise(function(resolve) {
                getSessionId(function(sid) {
                    if (!sid) {
                        resolve(null);
                        return;
                    }
                    fetch(apiBase + '/api/momot/stop', {
                        method: 'POST',
                        headers: { 'X-Session-ID': sid }
                    }).then(function(r) {
                        return r.json ? r.json() : r;
                    }).then(resolve).catch(function(e) {
                        console.error('[webBridge] stopMomotRun error', e);
                        resolve(null);
                    });
                });
            });
        },

        isMomotRunning: function() {
            return !!momotIsRunning;
        },

        listMomotSolutions: function() {
            fetchSolutions();
            if (cachedSolutions && cachedSolutions.length) {
                return JSON.stringify(cachedSolutions);
            }
            // An empty cache must not keep the previous level's rows on screen.
            return '[]';
        },

        getSolutionPath: function(modelPath) {
            var xhr = new XMLHttpRequest();
            xhr.open('POST', apiBase + '/api/momot/solutionPath', false);
            xhr.setRequestHeader('Content-Type', 'application/json');
            if (sessionId) xhr.setRequestHeader('X-Session-ID', sessionId);
            try {
                xhr.send(JSON.stringify({ modelPath: modelPath }));
                if (xhr.status === 200) {
                    var res = JSON.parse(xhr.responseText);
                    return JSON.stringify(res.path || []);
                }
            } catch(e) {
                console.error('[webBridge] getSolutionPath failed:', e);
            }
            return '[]';
        },

        loadMomotSolution: function(modelPath) {
            var info = getTimerSessionInfo();
            getSessionId(function(sid) {
                if (!sid) {
                    if (typeof window.__momotSetStatus === 'function') {
                        window.__momotSetStatus('Session error');
                    }
                    return;
                }
                fetch(apiBase + '/api/momot/load', {
                    method: 'POST',
                    headers: attachTimerHeaders({ 'Content-Type': 'application/json', 'X-Session-ID': sid }),
                    body: JSON.stringify({
                        modelPath: modelPath,
                        timerSessionId: info.timerSessionId,
                        level: info.level
                    })
                }).then(function(r) { return r.json(); })
                .then(function(data) {
                    if (data && data.status === 'ok') {
                        if (data.levelId !== undefined) {
                            window.K = data.levelId;
                        }
                        if (data.grid && Array.isArray(data.grid) && data.grid.length > 0) {
                            window.X = data.grid;
                        }
                        if (data.startPos && typeof data.startPos.x === 'number') {
                            window.nd = { x: data.startPos.x, y: data.startPos.y };
                            window.Q = data.startPos.x;
                            window.S = data.startPos.y;
                        }
                        if (data.goalPos && typeof data.goalPos.x === 'number') {
                            window.od = { x: data.goalPos.x, y: data.goalPos.y };
                        }
                        if (typeof data.startDirection === 'number') {
                            window.T = data.startDirection;
                            window.modelStartT = data.startDirection;
                            window.__modelStartT = data.startDirection;
                            window.__stableStartT = data.startDirection;
                        }
                        if (typeof window.$d === 'function') {
                            try { window.$d(false); } catch(eD) {}
                        }
                        if (data.xml) {
                            applyXmlToWorkspace(data.xml);
                        }
                        if (typeof window.__momotSetStatus === 'function') {
                            var modelName = (modelPath || '').split(/[\/\\]/).pop();
                            window.__momotSetStatus('Loaded: ' + modelName);
                        }
                    } else {
                        if (typeof window.__momotSetStatus === 'function') {
                            window.__momotSetStatus('Failed to load model from server.');
                        }
                    }
                })
                .catch(function(e) {
                    console.error('[webBridge] loadMomotSolution error', e);
                    if (typeof window.__momotSetStatus === 'function') {
                        window.__momotSetStatus('Load failed: ' + e);
                    }
                });
            });
        },

        teleportPegman: function(q, s, t) {
            var info = getTimerSessionInfo();
            return new Promise(function(resolve, reject) {
                getSessionId(function(sid) {
                    if (!sid) {
                        resolve(null);
                        return;
                    }
                    fetch(apiBase + '/api/dm/request', {
                        method: 'POST',
                        headers: attachTimerHeaders({ 'Content-Type': 'application/json', 'X-Session-ID': sid }),
                        body: JSON.stringify({
                            q: q,
                            s: s,
                            t: t,
                            timerSessionId: info.timerSessionId,
                            level: info.level
                        })
                    }).then(function(res) {
                        if (res && typeof res.ok === 'boolean' && !res.ok) {
                            throw new Error('HTTP ' + res.status);
                        }
                        return res.json ? res.json() : res;
                    }).then(resolve).catch(reject);
                });
            });
        },

        debugStart: function(q, s, t) {
            localDbg.startX = getStartX(q);
            localDbg.startY = getStartY(s);
            localDbg.startT = getStartT(t);

            window.Q = localDbg.startX;
            window.S = localDbg.startY;
            window.T = localDbg.startT;

            window.nd = { x: localDbg.startX, y: localDbg.startY };
            var goalObj = findGoalFromGrid();
            if (goalObj) {
                window.od = { x: goalObj.x, y: goalObj.y };
            }

            localDbg.active = true;
            localDbg.paused = true;
            localDbg.index = 0;

            var traceRes = computeLocalDebugTrace(localDbg.startX, localDbg.startY, localDbg.startT);
            localDbg.states = traceRes.states || [];
            if (localDbg.states && localDbg.states[0] && localDbg.states[0].logLine && window.__execLogAppend) {
                window.__execLogAppend([localDbg.states[0].logLine]);
            }

            var newPreview = compressRemainingPositions(localDbg.states, 0);
            window.__injectNewPath = newPreview;
            if (typeof window.__ifRender === 'function') {
                window.__ifRender();
            }

            return buildDebugFrameJson();
        },

        debugStep: function() {
            if (!localDbg.active || !localDbg.states || !localDbg.states.length) {
                this.debugStart(getStartX(), getStartY(), getStartT());
            } else {
                var traceRes = computeLocalDebugTrace(localDbg.startX, localDbg.startY, localDbg.startT);
                localDbg.states = traceRes.states || [];
            }
            var maxIdx = localDbg.states.length - 1;
            if (localDbg.index < maxIdx) {
                localDbg.index++;
                var st = localDbg.states[localDbg.index];
                if (st && st.logLine && window.__execLogAppend) {
                    window.__execLogAppend([st.logLine]);
                }
            }
            localDbg.paused = true;

            var newPreview = compressRemainingPositions(localDbg.states, localDbg.index);
            window.__injectNewPath = newPreview;
            if (typeof window.__ifRender === 'function') {
                window.__ifRender();
            }

            return buildDebugFrameJson();
        },

        debugTogglePause: function() {
            if (!localDbg.active || !localDbg.states || !localDbg.states.length) {
                this.debugStart(getStartX(), getStartY(), getStartT());
            }
            localDbg.paused = !localDbg.paused;
            return buildDebugFrameJson();
        },

        debugStop: function() {
            var sx = getStartX();
            var sy = getStartY();
            var st = getStartT();

            localDbg.startX = sx;
            localDbg.startY = sy;
            localDbg.startT = st;
            localDbg.index = 0;
            localDbg.paused = true;
            localDbg.active = false;

            window.Q = sx;
            window.S = sy;
            window.T = st;
            window.nd = { x: sx, y: sy };

            var traceRes = computeLocalDebugTrace(sx, sy, st);
            localDbg.states = traceRes.states || [];

            var newPreview = compressRemainingPositions(localDbg.states, 0);
            window.__injectNewPath = newPreview;

            if (window.__execLogClear) window.__execLogClear();
            if (typeof window.__ifRender === 'function') {
                window.__ifRender();
            }

            return buildDebugFrameJson();
        },

        debugSkipToEnd: function() {
            if (!localDbg.active || !localDbg.states || !localDbg.states.length) {
                this.debugStart(getStartX(), getStartY(), getStartT());
            } else {
                var traceRes = computeLocalDebugTrace(localDbg.startX, localDbg.startY, localDbg.startT);
                localDbg.states = traceRes.states || [];
            }
            localDbg.index = localDbg.states.length - 1;
            localDbg.paused = true;
            if (localDbg.states[localDbg.index] && localDbg.states[localDbg.index].logLine && window.__execLogAppend) {
                window.__execLogAppend([localDbg.states[localDbg.index].logLine]);
            }

            var newPreview = compressRemainingPositions(localDbg.states, localDbg.index);
            window.__injectNewPath = newPreview;
            if (typeof window.__ifRender === 'function') {
                window.__ifRender();
            }

            return buildDebugFrameJson();
        },

        debugTick: function() {
            if (!localDbg.active || !localDbg.states || !localDbg.states.length) {
                return buildDebugFrameJson();
            }
            if (!localDbg.paused) {
                var traceRes = computeLocalDebugTrace(localDbg.startX, localDbg.startY, localDbg.startT);
                localDbg.states = traceRes.states || [];

                var maxIdx = localDbg.states.length - 1;
                if (localDbg.index < maxIdx) {
                    localDbg.index++;
                    var st = localDbg.states[localDbg.index];
                    if (st && st.logLine && window.__execLogAppend) {
                        window.__execLogAppend([st.logLine]);
                    }
                } else {
                    localDbg.paused = true;
                }
            }

            var newPreview = compressRemainingPositions(localDbg.states, localDbg.index);
            window.__injectNewPath = newPreview;
            if (typeof window.__ifRender === 'function') {
                window.__ifRender();
            }

            return buildDebugFrameJson();
        },

        getStartX: function(q) { return getStartX(q); },
        getStartY: function(s) { return getStartY(s); },
        getStartT: function(t) { return getStartT(t); }
    };

    function callDebugSync(endpoint, payload) {
        var xhr = new XMLHttpRequest();
        xhr.open('POST', apiBase + endpoint, false);
        xhr.setRequestHeader('Content-Type', 'application/json');
        if (sessionId) xhr.setRequestHeader('X-Session-ID', sessionId);
        try {
            xhr.send(JSON.stringify(payload));
            if (xhr.status === 200) {
                return xhr.responseText;
            }
        } catch (e) {
            console.error('[webBridge] Debug call failed:', endpoint, e);
        }
        return '{}';
    }

    var solutionsFetchGen = 0;
    function fetchSolutions() {
        var gen = ++solutionsFetchGen;
        getSessionId(function(sid) {
            if (!sid) return;
            fetch(apiBase + '/api/momot/solutions', {
                headers: { 'X-Session-ID': sid }
            }).then(function(r) { return r.json(); })
            .then(function(data) {
                if (gen !== solutionsFetchGen) return;
                if (Array.isArray(data)) {
                    cachedSolutions = data;
                    if (typeof window.__momotRenderSolutions === 'function') {
                        window.__momotRenderSolutions(data);
                    }
                }
            }).catch(function(e) {});
        });
    }

    var pollInterval = null;
    var pollGeneration = 0;
    var momotFinishRetryTimer = null;
    var lastMomotLogIndex = 0;

    function resetMomotClientState() {
        pollGeneration++;
        solutionsFetchGen++;
        if (pollInterval) {
            clearInterval(pollInterval);
            pollInterval = null;
        }
        if (momotFinishRetryTimer) {
            clearTimeout(momotFinishRetryTimer);
            momotFinishRetryTimer = null;
        }
        cachedSolutions = [];
        momotIsRunning = false;
        window.__momotIsRunning = false;
        if (typeof window.__momotClearSolutions === 'function') {
            window.__momotClearSolutions();
        }
    }

    function finishMomotPolling(generation) {
        if (generation !== pollGeneration) return;
        momotIsRunning = false;
        window.__momotIsRunning = false;
        if (pollInterval) {
            clearInterval(pollInterval);
            pollInterval = null;
        }
        if (typeof window.__momotProgressDone === 'function') {
            window.__momotProgressDone();
        }
        fetchSolutions();
        if (momotFinishRetryTimer) clearTimeout(momotFinishRetryTimer);
        momotFinishRetryTimer = setTimeout(function() {
            momotFinishRetryTimer = null;
            if (generation !== pollGeneration) return;
            fetchSolutions();
        }, 400);
    }

    function startStatusPolling() {
        if (pollInterval) clearInterval(pollInterval);
        if (momotFinishRetryTimer) {
            clearTimeout(momotFinishRetryTimer);
            momotFinishRetryTimer = null;
        }
        pollGeneration++;
        var generation = pollGeneration;
        var seenRunning = false;
        lastMomotLogIndex = 0;
        if (typeof window.__momotLogClear === 'function') window.__momotLogClear();

        function pollOnce() {
            getSessionId(function(sid) {
                if (!sid || generation !== pollGeneration) return;
                fetch(apiBase + '/api/momot/status', {
                    headers: { 'X-Session-ID': sid }
                }).then(function(r) {
                    if (r && typeof r.ok === 'boolean' && !r.ok) {
                        throw new Error('HTTP ' + r.status);
                    }
                    return r.json();
                })
                .then(function(data) {
                    if (!data || generation !== pollGeneration) return;
                    if (data.progress && typeof window.__momotSetProgress === 'function') {
                        var p = data.progress;
                        window.__momotSetProgress(p.run, p.totalRuns, p.gen, p.totalGens, p.pct);
                    }
                    if (data.logs && window.__momotLogAppend) {
                        for (var i = lastMomotLogIndex; i < data.logs.length; i++) {
                            window.__momotLogAppend(data.logs[i]);
                        }
                        lastMomotLogIndex = data.logs.length;
                    }
                    if (data.status && window.__momotSetStatus) {
                        window.__momotSetStatus('MoMoT: ' + data.status);
                    }
                    if (data.running) {
                        momotIsRunning = true;
                        window.__momotIsRunning = true;
                        seenRunning = true;
                        fetchSolutions();
                        return;
                    }
                    var status = data.status || '';
                    var finished = status === 'Finished' || status === 'Stopped';
                    if (finished || (seenRunning && !data.running)) {
                        momotIsRunning = false;
                        window.__momotIsRunning = false;
                        finishMomotPolling(generation);
                    }
                }).catch(function(e) {
                    if (generation !== pollGeneration) return;
                    console.error('[webBridge] status polling error', e);
                    if (typeof window.__momotSetStatus === 'function') {
                        var msg = (e && e.message) ? e.message : String(e);
                        window.__momotSetStatus('MoMoT status error: ' + msg);
                    }
                });
            });
        }

        pollOnce();
        pollInterval = setInterval(pollOnce, 1000);
    }

    window.__blockyResetMomotSession = function() {
        pollGeneration++;
        solutionsFetchGen++;
        if (pollInterval) {
            clearInterval(pollInterval);
            pollInterval = null;
        }
        if (momotFinishRetryTimer) {
            clearTimeout(momotFinishRetryTimer);
            momotFinishRetryTimer = null;
        }
        cachedSolutions = [];
        momotIsRunning = false;
        window.__momotIsRunning = false;
        sessionId = null;
        try {
            localStorage.removeItem('blocky_session_id');
        } catch (e) {}
        if (typeof window.__momotClearSolutions === 'function') {
            window.__momotClearSolutions();
        }
    };

    var suppressSyncUntil = 0;

    function applyXmlToWorkspace(xmlStr) {
        if (!xmlStr) return;
        suppressSyncUntil = Date.now() + 500;
        window.__lastObservedXml = xmlStr;
        window.__lastSyncedXml = xmlStr;
        emptyWorkspacePollCount = 0;
        try {
            if (window.BlocklyInterface && typeof window.BlocklyInterface.setCode === 'function') {
                window.BlocklyInterface.setCode(xmlStr);
                console.log('[webBridge] Injected solution XML via BlocklyInterface.setCode');
                return;
            }
        } catch (e) {
            console.warn('[webBridge] BlocklyInterface.setCode failed:', e);
        }
        try {
            var ws = (window.BlocklyInterface && window.BlocklyInterface.getWorkspace && window.BlocklyInterface.getWorkspace()) ||
                     (window.Blockly && window.Blockly.getMainWorkspace && window.Blockly.getMainWorkspace());
            if (!ws) return;
            if (window.Blockly && window.Blockly.Xml && typeof window.Blockly.Xml.textToDom === 'function') {
                ws.clear();
                var dom = window.Blockly.Xml.textToDom(xmlStr);
                window.Blockly.Xml.domToWorkspace(dom, ws);
                console.log('[webBridge] Injected solution XML via window.Blockly.Xml');
            }
        } catch (e2) {
            console.error('[webBridge] Error injecting solution XML:', e2);
        }
    }

    function getCurrentWorkspaceXml() {
        try {
            if (window.BlocklyInterface && typeof window.BlocklyInterface.getCode === 'function') {
                var s = window.BlocklyInterface.getCode();
                if (s && typeof s === 'string' && s.indexOf('<xml') !== -1) return s;
            }
        } catch(e) {}

        try {
            var ws = (window.BlocklyInterface && window.BlocklyInterface.getWorkspace && window.BlocklyInterface.getWorkspace()) ||
                     (window.Blockly && window.Blockly.getMainWorkspace && window.Blockly.getMainWorkspace());
            if (ws) {
                if (typeof ws.getAllBlocks === 'function' && ws.getAllBlocks().length === 0) {
                    return '<xml></xml>';
                }
                if (window.Blockly && window.Blockly.Xml) {
                    var dom = window.Blockly.Xml.workspaceToDom(ws);
                    if (dom) {
                        return new XMLSerializer().serializeToString(dom);
                    }
                }
            }
        } catch(e) {}

        return window.__lastSyncedXml || '';
    }

    var emptyWorkspacePollCount = 0;

    function updateWorkspaceAndTrace() {
        try {
            if (Date.now() < suppressSyncUntil) {
                return;
            }
            var xmlStr = getCurrentWorkspaceXml();
            var hasBlocks = xmlStr && xmlStr.indexOf('<block') >= 0;
            var frontendK = (typeof window.K === 'number') ? window.K : 1;

            if (hasBlocks) {
                emptyWorkspacePollCount = 0;
                try {
                    if (window.sessionStorage) {
                        window.sessionStorage.removeItem('blocky_cleared_' + frontendK);
                    }
                } catch(e) {}
            } else {
                emptyWorkspacePollCount++;
            }

            if (xmlStr && xmlStr !== window.__lastObservedXml) {
                window.__lastObservedXml = xmlStr;

                var sx = getStartX();
                var sy = getStartY();
                var st = getStartT();

                if (!localDbg.active) {
                    localDbg.startX = sx;
                    localDbg.startY = sy;
                    localDbg.startT = st;
                }

                var traceRes = computeLocalDebugTrace(localDbg.startX, localDbg.startY, localDbg.startT);
                localDbg.states = traceRes.states || [];

                if (localDbg.active) {
                    if (localDbg.index >= localDbg.states.length) {
                        localDbg.index = Math.max(0, localDbg.states.length - 1);
                    }
                } else {
                    localDbg.index = 0;
                }

                var newPreview = compressRemainingPositions(localDbg.states, localDbg.active ? localDbg.index : 0);
                window.__injectNewPath = newPreview;

                if (typeof window.__ifRender === 'function') {
                    window.__ifRender();
                }
            } else if (!hasBlocks && window.__lastObservedXml && window.__lastObservedXml.indexOf('<block') >= 0) {
                window.__lastObservedXml = xmlStr;
                window.__injectNewPath = [];
                if (typeof window.__ifRender === 'function') {
                    window.__ifRender();
                }
            }

            if (hasBlocks) {
                if (xmlStr !== window.__lastSyncedXml) {
                    window.__lastSyncedXml = xmlStr;
                    if (window.javaBridge && typeof window.javaBridge.syncModel === 'function') {
                        window.javaBridge.syncModel(xmlStr, false);
                    }
                }
            } else if (emptyWorkspacePollCount >= 2
                    && window.__lastSyncedXml
                    && String(window.__lastSyncedXml).indexOf('<block') >= 0) {
                window.__lastSyncedXml = '';
                try {
                    if (window.localStorage) {
                        window.localStorage.removeItem('maze' + frontendK);
                    }
                    if (window.sessionStorage) {
                        window.sessionStorage.setItem('blocky_cleared_' + frontendK, '1');
                    }
                } catch(e) {}
                if (window.javaBridge && typeof window.javaBridge.syncModel === 'function') {
                    window.javaBridge.syncModel('', true);
                }
            }
        } catch(e) {}
    }
    window.__updateWorkspaceAndTrace = updateWorkspaceAndTrace;

    window.javaBridge = bridge;

    // Periodically update trace and sync workspace XML
    var syncTimer = setInterval(function() {
        updateWorkspaceAndTrace();
    }, 150);
    if (syncTimer && typeof syncTimer.unref === 'function') {
        syncTimer.unref();
    }

    try {
        var ws = (window.BlocklyInterface && window.BlocklyInterface.getWorkspace && window.BlocklyInterface.getWorkspace()) ||
                 (window.Blockly && window.Blockly.getMainWorkspace && window.Blockly.getMainWorkspace());
        if (ws && typeof ws.addChangeListener === 'function') {
            ws.addChangeListener(function() {
                updateWorkspaceAndTrace();
            });
        }
    } catch(e) {}

})();
