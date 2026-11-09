---
name: verify-pdf-outline
description: "Run bounded PDF Split outline model after changes to src/main/java/com/m1racle/reader/pdfsplit/split/OutlinePlanner.kt; source snapshot 52517b6."
---

# Chapter-only page ownership

Source mapping: `src/main/java/com/m1racle/reader/pdfsplit/split/OutlinePlanner.kt`, originally inspected at commit `52517b655e8b3edcf74ac5e097400784012ca7f4`.

Invariant: No selected chapter output may contain any page owned solely by an unselected chapter.

From repository root: `python3 tools/formal/verify_models.py`.

The command tests the abstract safe design and a deliberately broken control. Both must behave as expected. It does **not** verify compiled Kotlin; rerun domain tests and on-device cases for any source code change. After changes to the mapped file, read the exact diff, update the model if necessary, add regression evidence, then advance the sync commit. Use pstack `/statechart` when actual TLC specs are available; do not mislabel hand-authored Mermaid as TLC output.

Known limitations: Current implementation uses next selected anchor, not next actual chapter. Needs Kotlin fixture tests.
