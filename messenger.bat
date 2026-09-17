@echo off
rem ============================================================
rem  EasyShare Messenger - IP Messenger style LAN messaging and file sending
rem
rem  Run this on every PC on the same network. Other PCs appear
rem  automatically; select one, type a message, attach files/folders
rem  (or drag them onto the window) and click Send.
rem
rem  To try it with two windows on ONE PC:  messenger-second.bat
rem ============================================================
cd /d "%~dp0"
if not exist EasyShare.jar call "%~dp0build.bat" || exit /b 1
where javaw >nul 2>nul
if errorlevel 1 (
    java -jar EasyShare.jar messenger %*
) else (
    start "" javaw -jar EasyShare.jar messenger %*
)
