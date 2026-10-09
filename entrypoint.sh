#!/bin/bash
set -e

# Setup JVM flags
export JAVA_TOOL_OPTIONS="--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.xml/com.sun.org.apache.xerces.internal.jaxp=ALL-UNNAMED --add-exports=java.xml/com.sun.org.apache.xerces.internal.jaxp=ALL-UNNAMED -Djava.util.Arrays.useLegacyMergeSort=true"
# BLOCKY_OBJECTIVES: GATED (default; Edits, Actions and Blocks only count for candidates that reach the goal) or CURRENT
export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Dblocky.objectives=${BLOCKY_OBJECTIVES:-GATED}"
# BLOCKY_WRAP: true (default) or false (true = use the *_wrap.henshin rule files with wrap/unwrap moves)
export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Dblocky.rules.wrap=${BLOCKY_WRAP:-true}"
export MAVEN_OPTS="$JAVA_TOOL_OPTIONS"

# Always start virtual Xvfb display so JavaFX components run headlessly without DISPLAY errors
echo "Starting Xvfb virtual display..."
Xvfb :99 -screen 0 1280x1024x24 &
export DISPLAY=:99
sleep 1

# Start x11vnc and noVNC (web VNC client on port 6080)
x11vnc -display :99 -forever -nopw -shared -xkb &
ln -s /usr/share/novnc/vnc.html /usr/share/novnc/index.html || true
websockify --web /usr/share/novnc 0.0.0.0:6080 127.0.0.1:5900 &

HTTP_PORT="${PORT:-8080}"
echo "Starting Blocky Maze HTTP REST API & Web Server on port ${HTTP_PORT}..."
cd /app/blocky_game && java -Dserver.mode=true -cp "target/blocky-game-1.0.0-SNAPSHOT.jar:target/all_deps/*" blocky_game.Main --server "${HTTP_PORT}" &
HTTP_PID=$!

# Optionally start desktop GUI in Xvfb if START_GUI=true
if [ "$START_GUI" = "true" ]; then
    echo "Starting JavaFX desktop GUI in Xvfb..."
    mvn -pl blocky_game javafx:run &
fi

wait $HTTP_PID
