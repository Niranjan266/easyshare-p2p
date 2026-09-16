@echo off
rem Adds a 4th peer "Dave" to the running demo (port 6004, web http://localhost:8004).
rem Dave can download from Carol while Carol herself is still downloading - a real swarm.
cd /d "%~dp0"
if not exist demo\dave\shared mkdir demo\dave\shared demo\dave\downloads
start "Dave (downloader)" cmd /k java -jar EasyShare.jar peer --name Dave --port 6004 --shared demo\dave\shared --downloads demo\dave\downloads --web 8004 --tracker 127.0.0.1:7000 --limit 300
timeout /t 3 /nobreak >nul
start "" http://localhost:8004/
