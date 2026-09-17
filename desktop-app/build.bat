@echo off
rem ============================================================
rem  EasyShare Messenger (desktop app) - build EasyShareMessenger.jar
rem  Requires JDK 17 or newer
rem ============================================================
setlocal
cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 (
    echo ERROR: javac not found. Install JDK 17+ and add its bin folder to PATH.
    exit /b 1
)

echo Compiling Java sources...
if exist out rmdir /s /q out
mkdir out
javac --release 17 -encoding UTF-8 -d out --source-path src src\easyshare\messenger\MessengerApp.java
if errorlevel 1 (
    echo BUILD FAILED
    exit /b 1
)

set "JAR=jar"
where jar >nul 2>nul
if errorlevel 1 set "JAR="
if not defined JAR if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jar.exe" set "JAR=%JAVA_HOME%\bin\jar.exe"
if not defined JAR (
    for /f "tokens=2 delims==" %%H in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.home"') do set "JHOME=%%H"
)
if not defined JAR if defined JHOME (
    for /f "tokens=* delims= " %%H in ("%JHOME%") do set "JHOME=%%H"
)
if not defined JAR if defined JHOME if exist "%JHOME%\bin\jar.exe" set "JAR=%JHOME%\bin\jar.exe"
if not defined JAR (
    echo ERROR: the 'jar' tool was not found. Set JAVA_HOME to your JDK folder.
    exit /b 1
)

echo Creating EasyShareMessenger.jar...
"%JAR%" --create --file EasyShareMessenger.jar --main-class easyshare.messenger.MessengerApp -C out .
if errorlevel 1 (
    echo BUILD FAILED
    exit /b 1
)
echo.
echo BUILD SUCCESSFUL: %~dp0EasyShareMessenger.jar
endlocal
