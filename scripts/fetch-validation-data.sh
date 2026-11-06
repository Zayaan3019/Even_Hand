#!/bin/sh
# Fetches one public dataset for the validation into validation-data/ (git-ignored), checks it against a pinned
# checksum, and prints DATA=<path> on stdout. None of it reaches the product.
#
#   sh scripts/fetch-validation-data.sh unt|asap|aes|jorgpt-en|jorgpt-es|cityu
set -eu
cd "$(dirname "$0")/.."
ZENODO_ASAP=https://zenodo.org/api/records/7641696/files
ZENODO_JORGPT=https://zenodo.org/api/records/18981627/files
CITYU_REV=d48b8210102763159a60ef70e3f32b1f27c4baa6
get() { curl -fsSL --retry 3 -o "$2" "$1"; }
check() { echo "$1  $3" | "$2" -c - >&2; }

case "$1" in
  unt)
    mkdir -p validation-data/unt
    get "http://web.eecs.umich.edu/~mihalcea/downloads/ShortAnswerGrading_v2.0.zip" validation-data/unt.zip
    check 5abee7ee804a2c5ae802b226230fb14c5bdb894f0f33bab6e20b4e7f1ed89247 sha256sum validation-data/unt.zip
    (cd validation-data/unt && unzip -q -o ../unt.zip)
    echo "DATA=validation-data/unt"
    ;;
  asap)
    mkdir -p validation-data/asap
    get "$ZENODO_ASAP/short_answers.xlsx/content" validation-data/asap/short_answers.xlsx
    check 933b95c6650055fa212ab92d734e9051 md5sum validation-data/asap/short_answers.xlsx
    echo "DATA=validation-data/asap/short_answers.xlsx"
    ;;
  aes)
    mkdir -p validation-data/aes
    get "$ZENODO_ASAP/essays.xlsx/content" validation-data/aes/essays.xlsx
    check 1992438ead9365d5a3199ff1786aef5f md5sum validation-data/aes/essays.xlsx
    echo "DATA=validation-data/aes/essays.xlsx"
    ;;
  jorgpt-en)
    mkdir -p validation-data/jorgpt
    get "$ZENODO_JORGPT/dataset_en.csv/content" validation-data/jorgpt/dataset_en.csv
    check 196825ce723a632b1b117efc187b0147 md5sum validation-data/jorgpt/dataset_en.csv
    echo "DATA=validation-data/jorgpt/dataset_en.csv"
    ;;
  jorgpt-es)
    mkdir -p validation-data/jorgpt
    get "$ZENODO_JORGPT/dataset_es.csv/content" validation-data/jorgpt/dataset_es.csv
    check aef84f2b9c91e65325da48aaa6ff2909 md5sum validation-data/jorgpt/dataset_es.csv
    echo "DATA=validation-data/jorgpt/dataset_es.csv"
    ;;
  cityu)
    for q in 1 2 3 4 5 6; do
      mkdir -p "validation-data/cityu/q$q"
      get "https://raw.githubusercontent.com/wenjing1170/llm_grader/$CITYU_REV/dataset_os/q$q/grading.json" "validation-data/cityu/q$q/grading.json"
    done
    want=ecffc513eee090a00b679a10c60ec33764fa5c33c03d3c998dfc62618fc1dffb
    got=$(cat validation-data/cityu/q1/grading.json validation-data/cityu/q2/grading.json validation-data/cityu/q3/grading.json \
      validation-data/cityu/q4/grading.json validation-data/cityu/q5/grading.json validation-data/cityu/q6/grading.json | sha256sum | cut -d' ' -f1)
    [ "$got" = "$want" ] || { echo "SHA-256 mismatch: not the expected CityU data." >&2; exit 1; }
    python3 scripts/cityu-to-csv.py validation-data/cityu validation-data/cityu/cityu.csv
    echo "DATA=validation-data/cityu/cityu.csv"
    ;;
  *)
    echo "unknown dataset: $1" >&2
    exit 2
    ;;
esac
