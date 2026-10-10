@echo off
rem Double-click to open Even Hand in your browser. Builds it the first time (needs Java 21+).
cd /d "%~dp0"
if not exist "eh-app\target\even-hand.jar" call mvnw.cmd -q package -DskipTests
java -jar "eh-app\target\even-hand.jar" serve
pause
