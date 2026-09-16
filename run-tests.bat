@echo off
rem Runs the automated end-to-end self-test (tracker + 7 peers inside one program).
cd /d "%~dp0"
if not exist EasyShare.jar call "%~dp0build.bat" || exit /b 1
java -jar EasyShare.jar selftest
pause
