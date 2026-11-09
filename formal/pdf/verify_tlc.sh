#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SPECS="$ROOT/formal/pdf/specs"
JAR="${TLA_JAR:-$HOME/.cache/tla2tools-v1.8.0.jar}"
mkdir -p "$(dirname "$JAR")"
if [[ ! -f "$JAR" ]]; then
  curl -fsSL --retry 3 https://github.com/tlaplus/tlaplus/releases/download/v1.8.0/tla2tools.jar -o "$JAR"
fi
echo "0b57675323babad8ab3815b1db9f30e75dac1cdb  $JAR" | sha1sum --check --status
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
for name in PdfSourceSession PdfOutlineSelection PdfAutoParser PdfSplitCleanup PdfOutputNaming PdfReleasePublication; do
  echo "TLC safe design: $name"
  java -Xmx1024m -cp "$JAR" tlc2.TLC -workers 1 -config "$SPECS/$name.cfg" "$SPECS/$name.tla" > "$OUT/$name.good.log" 2>&1 || {
    cat "$OUT/$name.good.log"; exit 1;
  }
  grep -q "Model checking completed. No error has been found." "$OUT/$name.good.log" || {
    cat "$OUT/$name.good.log"; exit 1;
  }
  sed 's/Correct = TRUE/Correct = FALSE/' "$SPECS/$name.cfg" > "$OUT/$name.bad.cfg"
  echo "TLC fault injection: $name must violate an invariant"
  if java -Xmx1024m -cp "$JAR" tlc2.TLC -workers 1 -config "$OUT/$name.bad.cfg" "$SPECS/$name.tla" > "$OUT/$name.bad.log" 2>&1; then
    cat "$OUT/$name.bad.log"; echo "FAIL: unsafe variant unexpectedly passed"; exit 1;
  fi
  if ! grep -Eiq 'Invariant .* (is violated|violated)' "$OUT/$name.bad.log"; then
    cat "$OUT/$name.bad.log"; echo "FAIL: unsafe variant did not reach an invariant violation"; exit 1;
  fi
done
echo "PASS: six bounded TLA+/TLC models and six negative controls."
echo "LIMIT: intended design models; not proofs of production Kotlin or real PDF correctness."
