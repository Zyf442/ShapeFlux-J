@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
if not exist dist\shapeflux-j.jar (
  call build.bat
  if errorlevel 1 exit /b 1
)
java -Dfile.encoding=UTF-8 -jar dist\shapeflux-j.jar %*
if errorlevel 1 pause
