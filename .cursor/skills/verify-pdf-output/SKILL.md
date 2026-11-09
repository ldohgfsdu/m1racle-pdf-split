---
name: verify-pdf-output
description: "Run bounded PDF Split output model after changes to src/main/java/com/m1racle/reader/pdfsplit/split/SafOutputDirectory.kt; source snapshot 52517b6."
---

# SAF output naming and listing

Source mapping: `src/main/java/com/m1racle/reader/pdfsplit/split/SafOutputDirectory.kt`, originally inspected at commit `52517b655e8b3edcf74ac5e097400784012ca7f4`.

Invariant: A failed directory listing must not authorize a write; actual names must be shown after provider rename.

From repository root: `python3 tools/formal/verify_models.py`.

The command tests the abstract safe design and a deliberately broken control. Both must behave as expected. It does **not** verify compiled Kotlin; rerun domain tests and on-device cases for any source code change. After changes to the mapped file, read the exact diff, update the model if necessary, add regression evidence, then advance the sync commit. Use pstack `/statechart` when actual TLC specs are available; do not mislabel hand-authored Mermaid as TLC output.

Known limitations: Existing implementation can swallow listing errors; work remains to bind the model to code.
