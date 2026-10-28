@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
set "SFX_DATASET_LIMIT=%~1"
set "SFX_DATASET_ID=%~2"
if "%SFX_DATASET_LIMIT%"=="" set "SFX_DATASET_LIMIT=65536"
if "%SFX_DATASET_ID%"=="" set "SFX_DATASET_ID=kdd99"
set "SFX_DATASET_OUTPUT=results\dataset-%SFX_DATASET_ID%-%SFX_DATASET_LIMIT%-%RANDOM%-%RANDOM%"
java -Dfile.encoding=UTF-8 -jar dist\shapeflux-j.jar dataset --dataset "%SFX_DATASET_ID%" --max-rows %SFX_DATASET_LIMIT% --file examples\rmsnorm.sfx --output "%SFX_DATASET_OUTPUT%"
if errorlevel 1 (
  pause
  exit /b 1
)
start "" "%SFX_DATASET_OUTPUT%\report.html"
pause
