@echo off
rem Opens a SECOND messenger window on the same PC so you can test sending to yourself.
cd /d "%~dp0"
call "%~dp0EasyShare Messenger.bat" --name "Test PC 2" --settings "%USERPROFILE%\.easyshare-messenger\second.properties" --receive "%USERPROFILE%\Downloads\EasyShare Received (Test PC 2)"
