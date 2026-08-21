@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM  Compilador de Create: Better Admin Shop (NeoForge 1.21.1)
REM ============================================================

cd /d "%~dp0"

echo.
echo ===========================================================
echo  Compilando betteradminshop para NeoForge 1.21.1...
echo ===========================================================
echo.

REM ---- Java check --------------------------------------------
where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] No se encontro 'java' en el PATH.
    echo         Instala JDK 21 y agregalo al PATH antes de continuar.
    pause
    exit /b 1
)
java -version
echo.

REM ---- Resolver JAVA_HOME real (carpeta del java.exe actual) -
REM Gradle a veces hereda un JAVA_HOME viejo apuntando a un JDK
REM desinstalado. Lo recalculamos en base al java.exe del PATH.
for /f "delims=" %%I in ('where java') do (
    set "JAVA_EXE=%%I"
    goto :gotjava
)
:gotjava
for %%I in ("%JAVA_EXE%\..\..") do set "JAVA_HOME=%%~fI"
echo JAVA_HOME = !JAVA_HOME!
echo.

REM ---- Matar daemons de Gradle previos (los que apuntan a JDKs
REM      borrados generan ruido y a veces cuelgues).
echo --- gradlew --stop ---
call gradlew.bat --stop 1>nul 2>nul

REM ---- Build limpio sin daemon -------------------------------
echo --- gradlew clean build ---
call gradlew.bat clean build --no-daemon -Dorg.gradle.java.home="!JAVA_HOME!"
set BUILD_RC=%ERRORLEVEL%

echo.
if %BUILD_RC% NEQ 0 (
    echo ===========================================================
    echo  COMPILACION FALLIDA  ^(codigo %BUILD_RC%^)
    echo ===========================================================
    echo.
    echo Si el error fue de Parchment ^(404 al bajar mappings^):
    echo   - abre gradle.properties
    echo   - cambia   use_parchment = true   por   use_parchment = false
    echo   - vuelve a ejecutar este .bat
    pause
    exit /b %BUILD_RC%
)

echo ===========================================================
echo  COMPILACION OK
echo ===========================================================
echo.
echo .jar generado en:
echo   %CD%\build\libs\
echo.
dir /b "build\libs\*.jar" 2>nul
echo.
pause
endlocal
