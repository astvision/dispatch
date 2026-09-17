@echo off
rem Runs Dispatch from dispatch.jar in this script's folder: dispatch init, dispatch check, dispatch run, ...
rem Uses %JAVA_HOME%\bin\java.exe when JAVA_HOME is set, otherwise java on PATH. Needs Java 25 or later.
setlocal
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
"%JAVA%" -jar "%~dp0dispatch.jar" %*
exit /b %ERRORLEVEL%
