@echo off
rem ============================================================
rem  Prepares the static website folder for hosting (e.g. Vercel):
rem   - copies USER_MANUAL.md into website\
rem   - creates website\downloads\EasyShare.zip (jar, scripts, sources, docs)
rem ============================================================
setlocal
cd /d "%~dp0"
call "%~dp0build.bat" || exit /b 1

if not exist website\downloads mkdir website\downloads
copy /y USER_MANUAL.md website\USER_MANUAL.md >nul

set "STAGE=%TEMP%\easyshare-package\EasyShare"
if exist "%TEMP%\easyshare-package" rmdir /s /q "%TEMP%\easyshare-package"
mkdir "%STAGE%"
xcopy /e /i /q src "%STAGE%\src" >nul
copy /y EasyShare.jar "%STAGE%" >nul
copy /y *.bat "%STAGE%" >nul
copy /y README.md "%STAGE%" >nul
copy /y USER_MANUAL.md "%STAGE%" >nul
del "%STAGE%\package-website.bat"

if exist website\downloads\EasyShare.zip del website\downloads\EasyShare.zip
powershell -NoProfile -Command "Compress-Archive -Path '%STAGE%' -DestinationPath 'website\downloads\EasyShare.zip'"
if errorlevel 1 (
    echo Packaging failed
    exit /b 1
)
rmdir /s /q "%TEMP%\easyshare-package"
echo Website ready in %~dp0website
endlocal
