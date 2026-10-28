@echo off
setlocal
cd /d "%~dp0"
java tools\Build.java %*
if errorlevel 1 exit /b 1
