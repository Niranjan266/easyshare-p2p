@echo off
rem ============================================================
rem  Start the EasyShare tracker (peer discovery service)
rem  Usage:  start-tracker.bat            (port 7000)
rem          start-tracker.bat 7100       (custom port)
rem ============================================================
cd /d "%~dp0"
if not exist EasyShare.jar call "%~dp0build.bat" || exit /b 1
title EasyShare Tracker
set "PORT=%~1"
if "%PORT%"=="" set "PORT=7000"
java -jar EasyShare.jar tracker --port %PORT%
