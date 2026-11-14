@echo off
set JAVA_CMD=java
where java >nul 2>nul
if %ERRORLEVEL% neq 0 (
    if exist "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe" (
        set JAVA_CMD="C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe"
    )
)
%JAVA_CMD% -Xmx512M -cp "target;lib/ECLA.jar;lib/DTNConsoleConnection.jar" core.DTNSim %*
