#!/bin/sh
# Downloads the sentence-embedding model Even Hand reads answers with (all-MiniLM-L6-v2, 87 MB, Apache-2.0)
# to ~/.even-hand/models, pinned to the exact revision the validation was run on, and checks every file's
# SHA-256. Run once with internet; Even Hand itself never goes online. Safe to run again: it skips a model
# that is already present and correct.
set -eu
REPO=sentence-transformers/all-MiniLM-L6-v2
REV=1110a243fdf4706b3f48f1d95db1a4f5529b4d41
DIR="${EVEN_HAND_MODEL:-$HOME/.even-hand/models/all-MiniLM-L6-v2}"
FILES="config.json:953f9c0d463486b10a6871cc2fd59f223b2c70184f49815e7efbcab5d8908b41
vocab.txt:07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3
model.safetensors:53aa51172d142c89d9012cce15ae4d6cc0ca6895895114379cacb4fab128d9db
tokenizer_config.json:acb92769e8195aabd29b7b2137a9e6d6e25c476a4f15aa4355c233426c61576b
sentence_bert_config.json:fc1993fde0a95c24ec6c022539d41cf6e2f7c9721e5415d6fb6897472a9cd4b7
1_Pooling/config.json:4be450dde3b0273bb9787637cfbd28fe04a7ba6ab9d36ac48e92b11e350ffc23"

sha() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

mkdir -p "$DIR/1_Pooling"
echo "$FILES" | while IFS=: read -r f want; do
  if [ -f "$DIR/$f" ] && [ "$(sha "$DIR/$f")" = "$want" ]; then continue; fi
  echo "Fetching $f ..."
  curl -fsSL --retry 3 -o "$DIR/$f.part" "https://huggingface.co/$REPO/resolve/$REV/$f"
  got="$(sha "$DIR/$f.part")"
  if [ "$got" != "$want" ]; then
    rm -f "$DIR/$f.part"
    echo "SHA-256 mismatch for $f (got $got). Not installed." >&2
    exit 1
  fi
  mv "$DIR/$f.part" "$DIR/$f"
done
echo "Embedding model ready in $DIR"
