---
name: verify-pdf-source
description: "Run bounded PDF Split source model after changes to src/main/java/com/m1racle/reader/pdfsplit/ui/SplitViewModel.kt; source snapshot 52517b6."
---

# Input session isolation

Source mapping: `src/main/java/com/m1racle/reader/pdfsplit/ui/SplitViewModel.kt`, originally inspected at commit `52517b655e8b3edcf74ac5e097400784012ca7f4`.

Invariant: No completion from a previously selected PDF may replace the newest source or its rules.

From repository root: `python3 tools/formal/verify_models.py`.

The command tests the abstract safe design and a deliberately broken control. Both must behave as expected. It does **not** verify compiled Kotlin; rerun domain tests and on-device cases for any source code change. After changes to the mapped file, read the exact diff, update the model if necessary, add regression evidence, then advance the sync commit. Use pstack `/statechart` when actual TLC specs are available; do not mislabel hand-authored Mermaid as TLC output.

Known limitations: Current production violation: stale loading and previous outline state; needs instrumentation tests.
