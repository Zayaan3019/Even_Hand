@echo off
rem Double-click to open Even Hand in your browser (needs Java 21+).
rem It rebuilds itself whenever the code is newer than the last build, so it always runs the latest version.
cd /d "%~dp0"
set JAR=eh-app\target\even-hand.jar

where java >nul 2>nul
if errorlevel 1 (
  echo Even Hand needs Java 21 or newer, and this computer does not have it.
  echo Install it from https://adoptium.net ^(choose "JDK 21"^), then double-click this file again.
  pause
  exit /b 1
)

rem The Maven wrapper needs JAVA_HOME, which the Java installer leaves unset by default: ask Java itself.
if not defined JAVA_HOME (
  for /f "tokens=2 delims==" %%h in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.home"') do (
    for /f "tokens=*" %%t in ("%%h") do set "JAVA_HOME=%%t"
  )
)

rem Exit code 0: the jar is up to date. 1: missing, or some source or pom.xml has changed since it was built.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$j = Get-Item '%JAR%' -ErrorAction SilentlyContinue; if (-not $j) { exit 1 }; $src = @(Get-Item 'pom.xml','eh-*\pom.xml') + @(Get-Item 'eh-*\src' | Get-ChildItem -Recurse -File); if ($src | Where-Object { $_.LastWriteTime -gt $j.LastWriteTime } | Select-Object -First 1) { exit 1 } else { exit 0 }"
if errorlevel 1 (
  echo Building the latest version of Even Hand. This takes a minute or two.
  rem A previous version may still be running; stop it so the new one can open.
  powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8642 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue } | Where-Object { $_.ProcessName -like 'java*' } | Stop-Process -Force"
  call "%~dp0mvnw.cmd" -q clean package -DskipTests
  if errorlevel 1 (
    echo The build failed. The messages above say why.
    pause
    exit /b 1
  )
)

java -jar "%JAR%" serve
pause
