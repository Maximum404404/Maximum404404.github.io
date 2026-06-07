@echo off
REM ============================================================
REM  Double-click this to refresh the radio playlist.
REM  Run it after adding or removing files in the Songs / DJI
REM  folders — it rebuilds radio-tracks.js for you.
REM ============================================================
cd /d "%~dp0"
echo Refreshing radio playlist from the Songs and DJI folders...
echo.
powershell -ExecutionPolicy Bypass -NoProfile -File "build-radio.ps1"
echo.
echo Done. You can close this window.
pause
