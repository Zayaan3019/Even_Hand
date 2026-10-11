@echo off
rem Double-click to open Even Hand in your browser. Builds it the first time (needs Java 21+).
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Even Hand needs Java 21 or newer, and this computer does not have it.
  echo Install it from https://adoptium.net ^(choose "JDK 21"^), then double-click this file again.
  pause
  exit /b 1
)
if not exist "eh-app\target\even-hand.jar" (
  echo First run: building Even Hand. This takes a minute or two and happens only once.
  call mvnw.cmd -q package -DskipTests
)
java -jar "eh-app\target\even-hand.jar" serve
pause
