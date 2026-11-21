@echo off
rem plm: start, stop and look after the whole platform (Windows). See plm.ps1, or run "plm" alone for help.
where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0plm.ps1" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0plm.ps1" %*
)
exit /b %ERRORLEVEL%
