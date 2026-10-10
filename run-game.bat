@echo off
echo Starting Blocky Maze Full Stack...
docker compose up -d --build
echo.
echo Waiting for the game to start...
timeout /t 5 >nul
echo.
echo Launching browser at http://localhost:8080/maze.html
start http://localhost:8080/maze.html
echo.
echo Done! You can close this window.
pause