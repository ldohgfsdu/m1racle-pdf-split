---
name: verify-pdf-split
description: "Run bounded PDF Split split model after changes to src/main/java/com/m1racle/reader/pdfsplit/split/PdfRangeSplitter.kt; source snapshot 52517b6."
---

# Cancellation cleanup integrity

Source mapping: `src/main/java/com/m1racle/reader/pdfsplit/split/PdfRangeSplitter.kt`, originally inspected at commit `52517b655e8b3edcf74ac5e097400784012ca7f4`.

Invariant: The UI must not report cancellation complete when SAF-created outputs remain undeleted.

From repository root: `python3 tools/formal/verify_models.py`.

The command tests the abstract safe design and a deliberately broken control. Both must behave as expected. It does **not** verify compiled Kotlin; rerun domain tests and on-device cases for any source code change. After changes to the mapped file, read the exact diff, update the model if necessary, add regression evidence, then advance the sync commit. Use pstack `/statechart` when actual TLC specs are available; do not mislabel hand-authored Mermaid as TLC output.

Known limitations: Production error-path handling remains to be checked with SAF failure injection.
