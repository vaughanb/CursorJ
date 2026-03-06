@echo off
setlocal

rem --- CursorJ: Launch IntelliJ with the plugin loaded ---

rem Auto-detect JAVA_HOME from IntelliJ's bundled JBR if not already set
if not defined JAVA_HOME (
    for /d %%D in ("C:\Program Files\JetBrains\IntelliJ IDEA *") do (
        if exist "%%D\jbr" (
            set "JAVA_HOME=%%D\jbr"
        )
    )
)

if not defined JAVA_HOME (
    echo ERROR: JAVA_HOME is not set and no JetBrains JBR was found.
    echo Please set JAVA_HOME to a JDK 21+ installation.
    exit /b 1
)

rem Use a separate Gradle home to avoid cache corruption from sandbox kills
set "GRADLE_USER_HOME=%USERPROFILE%\.gradle-cursorj"

echo Using JAVA_HOME: %JAVA_HOME%
echo Launching IntelliJ with CursorJ plugin...
echo.

call "%~dp0gradlew.bat" runIde %*
