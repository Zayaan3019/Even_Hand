#!/bin/sh
# Opens Even Hand in your browser (needs Java 21+).
# It rebuilds itself whenever the code is newer than the last build, so it always runs the latest version.
cd "$(dirname "$0")"
JAR=eh-app/target/even-hand.jar
if ! command -v java >/dev/null 2>&1; then
  echo "Even Hand needs Java 21 or newer. Install it from https://adoptium.net (JDK 21), then run this again."
  exit 1
fi
if [ ! -f "$JAR" ] || [ -n "$(find pom.xml eh-*/pom.xml eh-*/src -newer "$JAR" -type f 2>/dev/null | head -n 1)" ]; then
  echo "Building the latest version of Even Hand. This takes a minute or two."
  ./mvnw -q clean package -DskipTests || { echo "The build failed. The messages above say why."; exit 1; }
fi
exec java -jar "$JAR" serve
