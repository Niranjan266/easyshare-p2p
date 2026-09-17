@echo off
rem ============================================================
rem  EasyShare Messenger - IP Messenger style LAN app
rem  Run this on every PC in the same network. Other PCs appear
rem  automatically: select one, type a message, attach files or
rem  folders (or drag them onto the window) and click Send.
rem ============================================================
cd /d "%~dp0"
if not exist EasyShareMessenger.jar call "%~dp0build.bat" || (pause & exit /b 1)
where javaw >nul 2>nul
if errorlevel 1 (
    java -jar EasyShareMessenger.jar %*
) else (
    start "" javaw -jar EasyShareMessenger.jar %*
)
