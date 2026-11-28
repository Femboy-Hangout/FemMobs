@echo off
setlocal
set "JAVA_HOME=%USERPROFILE%\.jdks\ms-25.0.5.1"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo Java 25 was not found at "%JAVA_HOME%".
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
call "%~dp0gradlew.bat" clean build
if errorlevel 1 exit /b %errorlevel%
echo.
echo Plugin built: %~dp0build\libs\FemMobs-1.8.0.jar
