@echo off
rem Deletes the demo folders (sample files and all downloads) so the demo can start fresh.
rem Close all EasyShare windows first.
cd /d "%~dp0"
if exist demo (
    rmdir /s /q demo
    echo Demo folders deleted. Run demo.bat to start again.
) else (
    echo Nothing to clean.
)
