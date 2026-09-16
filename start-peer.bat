@echo off
rem ============================================================
rem  Start an EasyShare peer.
rem
rem  With no arguments it asks for the settings interactively.
rem  With arguments they are passed straight to the peer, e.g.
rem    start-peer.bat --name Alice --port 6001 --shared shared --downloads downloads --tracker 192.168.1.10:7000 --web 8001
rem ============================================================
cd /d "%~dp0"
if not exist EasyShare.jar call "%~dp0build.bat" || exit /b 1

if not "%~1"=="" (
    java -jar EasyShare.jar peer %*
    exit /b
)

echo ============================================
echo   EasyShare - start a peer
echo ============================================
set "NAME=%USERNAME%"
set /p "NAME=Your peer name        [%NAME%]: "
set "PORT=6001"
set /p "PORT=TCP port              [6001]: "
set "TRACKER=127.0.0.1:7000"
set /p "TRACKER=Tracker IP:port (or none) [127.0.0.1:7000]: "
set "WEB=8001"
set /p "WEB=Web dashboard port (or none) [8001]: "

set "FOLDER=peers\%NAME%"
if not exist "%FOLDER%\shared" mkdir "%FOLDER%\shared"
if not exist "%FOLDER%\downloads" mkdir "%FOLDER%\downloads"

set "ARGS=--name "%NAME%" --port %PORT% --shared "%FOLDER%\shared" --downloads "%FOLDER%\downloads""
if /i not "%TRACKER%"=="none" set "ARGS=%ARGS% --tracker %TRACKER%"
if /i not "%WEB%"=="none" set "ARGS=%ARGS% --web %WEB%"

echo.
echo Put the files you want to share in: %~dp0%FOLDER%\shared
if /i not "%WEB%"=="none" start "" http://localhost:%WEB%/
title EasyShare Peer - %NAME%
java -jar EasyShare.jar peer %ARGS%
