@echo off
chcp 65001 >nul
setlocal
pushd "%~dp0"
set "SFX_CHECK_DIR=results\feature-checks-%RANDOM%-%RANDOM%"
echo 正在构建并验证全部本地检查，然后验证新版功能入口、进度、报告和取消状态。
java -Dfile.encoding=UTF-8 tools\Build.java test
if errorlevel 1 goto failed
java -Dfile.encoding=UTF-8 -cp dist\shapeflux-j.jar tools\ValidateCompletionRelease.java "%SFX_CHECK_DIR%"
if errorlevel 1 goto failed
echo 验证完成，记录保存在 %SFX_CHECK_DIR%
popd
pause
exit /b 0
:failed
echo 验证未通过，请查看上面的错误和结果目录日志。
popd
pause
exit /b 1
