@echo off
rem ============================================================
rem  EasyShare phone sharing - one click
rem  Starts a peer whose files can be downloaded (and uploaded to)
rem  from any phone browser on the same Wi-Fi / hotspot.
rem   Shared folder : phone-share\shared   (put files here)
rem   Phone page    : http://<this PC's Wi-Fi IP>:9001/
rem   PC dashboard  : http://localhost:8001/
rem ============================================================
setlocal
cd /d "%~dp0"
if not exist EasyShare.jar call "%~dp0build.bat" || exit /b 1
if not exist phone-share\shared mkdir phone-share\shared
if not exist phone-share\downloads mkdir phone-share\downloads
if not exist phone-share\shared\welcome.txt (
    echo Hello from EasyShare!
    echo This file was downloaded from the PC to your phone over Wi-Fi.
) > phone-share\shared\welcome.txt

echo.
echo  1. Connect the phone to the SAME Wi-Fi as this PC (or connect this PC to the phone's hotspot).
echo  2. If Windows Firewall asks about Java, tick Private networks and click Allow.
echo  3. On the phone, open the http://...:9001/ address listed next to your Wi-Fi adapter below.
echo  4. Files you copy into %~dp0phone-share\shared appear on the phone (type 'refresh' here).
echo     Files uploaded from the phone are saved in that same folder.
echo.
title EasyShare - phone sharing
java -jar EasyShare.jar peer --name PC --port 6001 --shared phone-share\shared --downloads phone-share\downloads --web 8001 --phone 9001
endlocal
