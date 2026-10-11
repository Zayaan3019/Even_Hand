#!/bin/sh
# Opens Even Hand in your browser. Builds it the first time (needs Java 21+).
cd "$(dirname "$0")"
if ! command -v java >/dev/null 2>&1; then
  echo "Even Hand needs Java 21 or newer. Install it from https://adoptium.net (JDK 21), then run this again."
  exit 1
fi
if [ ! -f eh-app/target/even-hand.jar ]; then
  echo "First run: building Even Hand. This takes a minute or two and happens only once."
  ./mvnw -q package -DskipTests
fi
exec java -jar eh-app/target/even-hand.jar serve
