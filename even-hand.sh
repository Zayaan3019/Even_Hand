#!/bin/sh
# Opens Even Hand in your browser. Builds it the first time (needs Java 21+).
cd "$(dirname "$0")"
[ -f eh-app/target/even-hand.jar ] || ./mvnw -q package -DskipTests
exec java -jar eh-app/target/even-hand.jar serve
