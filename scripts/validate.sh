#!/bin/sh
# Reproduces docs/VALIDATION.md: Even Hand on real, independently double-graded student answers.
#
#   sh scripts/validate.sh [runs]      (default 100 deals per scenario; about 15 minutes on a laptop)
#
# Downloads the UNT Computer Science Short Answer Dataset v2.0 (Mohler, Bunescu and Mihalcea, ACL 2011)
# once into validation-data/ (git-ignored), checks its SHA-256, builds Even Hand, and runs eh-validation's
# UntValidation. Nothing here is part of the product: the dataset is used only to measure accuracy.
set -eu
cd "$(dirname "$0")/.."
RUNS="${1:-100}"
DIR=validation-data/unt
ZIP=validation-data/ShortAnswerGrading_v2.0.zip
WANT=5abee7ee804a2c5ae802b226230fb14c5bdb894f0f33bab6e20b4e7f1ed89247
sha() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

mkdir -p validation-data
if [ ! -f "$ZIP" ] || [ "$(sha "$ZIP")" != "$WANT" ]; then
  echo "Fetching the UNT short-answer dataset (1.4 MB) ..."
  curl -fsSL --retry 3 -o "$ZIP.part" "http://web.eecs.umich.edu/~mihalcea/downloads/ShortAnswerGrading_v2.0.zip"
  [ "$(sha "$ZIP.part")" = "$WANT" ] || { rm -f "$ZIP.part"; echo "SHA-256 mismatch: not the expected dataset." >&2; exit 1; }
  mv "$ZIP.part" "$ZIP"
fi
[ -d "$DIR/data" ] || { mkdir -p "$DIR"; (cd "$DIR" && unzip -q "../$(basename "$ZIP")"); }

sh scripts/fetch-model.sh
./mvnw -q -B install -DskipTests
./mvnw -q -B -pl eh-validation dependency:build-classpath -Dmdep.outputFile=target/cp.txt
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
CP="eh-validation/target/classes$SEP$(cat eh-validation/target/cp.txt)"
java --add-modules jdk.incubator.vector -cp "$CP" in.ac.iitm.evenhand.validation.UntValidation "$DIR" \
  --runs "$RUNS" --out docs/validation
echo "Results in docs/validation/VALIDATION-REAL.md (summary: docs/VALIDATION.md)"
