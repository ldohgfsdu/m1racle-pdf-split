---
name: verify-pdf-release
description: "Run bounded PDF Split release model after changes to .github/workflows/android-verify-publish.yml; source snapshot 52517b6."
---

# Verified release publication

Source mapping: `.github/workflows/android-verify-publish.yml`, originally inspected at commit `52517b655e8b3edcf74ac5e097400784012ca7f4`.

Invariant: No public release exists before APK, checksum and signer are validated from remote download.

From repository root: `python3 tools/formal/verify_models.py`.

The command tests the abstract safe design and a deliberately broken control. Both must behave as expected. It does **not** verify compiled Kotlin; rerun domain tests and on-device cases for any source code change. After changes to the mapped file, read the exact diff, update the model if necessary, add regression evidence, then advance the sync commit. Use pstack `/statechart` when actual TLC specs are available; do not mislabel hand-authored Mermaid as TLC output.

Known limitations: CI YAML is structural evidence; execute a controlled draft test before claiming release-path acceptance.
