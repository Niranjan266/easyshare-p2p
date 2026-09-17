@echo off
rem Opens a SECOND messenger window on the same PC (separate name and settings) to test sending to yourself.
cd /d "%~dp0"
call "%~dp0messenger.bat" --name "Test PC 2" --settings "%USERPROFILE%\.easyshare-messenger\second.properties" --receive "%USERPROFILE%\Downloads\EasyShare Received (Test PC 2)"
