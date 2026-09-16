@echo off
rem ============================================================
rem  EasyShare one-click demonstration on ONE computer
rem
rem   Window 1  Tracker  port 7000
rem   Window 2  Alice    port 6001  seeds sample-video.bin + notes.txt     web http://localhost:8001
rem   Window 3  Bob      port 6002  seeds sample-video.bin + dataset.bin   web http://localhost:8002
rem             (Bob corrupts 15%% of the pieces he sends, to demonstrate SHA-1 checking)
rem   Window 4  Carol    port 6003  empty - she downloads                  web http://localhost:8003
rem
rem  Uploads are limited to 300 KB/s so you can watch the chunks arrive.
rem ============================================================
setlocal
cd /d "%~dp0"

if not exist EasyShare.jar (
    call "%~dp0build.bat" || exit /b 1
)

if not exist demo\alice\shared\sample-video.bin (
    echo Creating demo folders and sample files...
    mkdir demo\alice\shared demo\alice\downloads demo\bob\shared demo\bob\downloads demo\carol\shared demo\carol\downloads demo\dave\shared demo\dave\downloads 2>nul
    java -jar EasyShare.jar makefile demo\alice\shared\sample-video.bin 12
    copy /y demo\alice\shared\sample-video.bin demo\bob\shared\sample-video.bin >nul
    java -jar EasyShare.jar makefile demo\bob\shared\dataset.bin 3
    (
        echo EasyShare demo notes
        echo ====================
        echo This text file was shared by Alice over the peer-to-peer network.
        echo It was split into pieces, every piece was checked with SHA-1,
        echo and the whole file was verified again after download.
    ) > demo\alice\shared\notes.txt
)

set "COMMON=--tracker 127.0.0.1:7000 --limit 300"

start "EasyShare Tracker" cmd /k java -jar EasyShare.jar tracker --port 7000
timeout /t 2 /nobreak >nul
start "Alice (seeder)" cmd /k java -jar EasyShare.jar peer --name Alice --port 6001 --shared demo\alice\shared --downloads demo\alice\downloads --web 8001 %COMMON%
start "Bob (seeder, sends 15%% corrupt pieces)" cmd /k java -jar EasyShare.jar peer --name Bob --port 6002 --shared demo\bob\shared --downloads demo\bob\downloads --web 8002 --corrupt 15 %COMMON%
timeout /t 3 /nobreak >nul
start "Carol (downloader)" cmd /k java -jar EasyShare.jar peer --name Carol --port 6003 --shared demo\carol\shared --downloads demo\carol\downloads --web 8003 %COMMON%
timeout /t 3 /nobreak >nul
start "" http://localhost:8003/

echo.
echo Demo started: 1 tracker + 3 peers in separate windows.
echo.
echo In the "Carol" window type:
echo     search
echo     get 3          (the number shown for sample-video.bin)
echo Or use Carol's web dashboard: http://localhost:8003/
echo.
echo Optional 4th peer (downloads from Carol too):  demo-dave.bat
echo Reset the demo (delete downloads):              clean-demo.bat
endlocal
