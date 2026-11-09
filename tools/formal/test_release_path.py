#!/usr/bin/env python3
"""Structural fail-closed release-path regression, not a network publication test."""
from pathlib import Path

script = Path("scripts/release.sh").read_text(encoding="utf-8")
workflow = Path(".github/workflows/android-verify-publish.yml").read_text(encoding="utf-8")

def script_contract(text: str) -> None:
    ordered = [
        '"$PY" tools/formal/verify_models.py',
        'bash formal/pdf/verify_tlc.sh',
        'clean testDebugUnitTest lintDebug assembleRelease',
        "'draft':True",
        'RELEASE_JSON="$(api POST',
        'ASSETS_JSON="$(api GET',
        'cmp -s "$APK_DIR/$APK_NAME.sha256"',
        'sha256sum --check "$APK_NAME.sha256"',
        'verify_signature "$TMPDIR_DL/$APK_NAME"',
        'api PATCH "/repos/$REPO_SLUG/releases/$RELEASE_ID"',
        'FINAL_JSON="$(api GET',
    ]
    indices = [text.find(t) for t in ordered]
    if -1 in indices or indices != sorted(indices):
        raise AssertionError("Local release steps missing or incorrectly ordered: " + repr(list(zip(ordered, indices))))
    if "'draft':False" in text:
        raise AssertionError("Legacy public release creation remains")
    for marker in ("[ \"$PUBLISHED\" = \"1\" ]", "[ \"$FINAL_OK\" = \"1\" ]", 'ASSET_SET_OK', 'FINAL_TAG_SHA'):
        if marker not in text:
            raise AssertionError("Missing final release verification: " + marker)

def workflow_contract(text: str) -> None:
    ordered = [
        "Six bounded TLA+ / TLC checks",
        "Signed draft, remote verification, public Release",
        'gh release create "$RELEASE_TAG" "$apk" "$apk.sha256" --draft',
        'gh release download "$RELEASE_TAG" --pattern "$apk"',
        'cmp "$apk" "$RUNNER_TEMP/readback/$apk"',
        'gh release edit "$RELEASE_TAG" --draft=false',
    ]
    # Jobs are defined before steps; only enforce relative order within the publish shell.
    markers = ordered[2:]
    indices = [text.find(t) for t in markers]
    if -1 in indices or indices != sorted(indices):
        raise AssertionError("CI draft publication/readback order lost: " + repr(list(zip(markers, indices))))
    for marker in ordered[:2]:
        if marker not in text:
            raise AssertionError("Required CI gate missing: " + marker)

script_contract(script)
workflow_contract(workflow)
# Mutation controls prove this check fails on two likely regressions.
for variant in (
    script.replace("'draft':True", "'draft':False"),
    script.replace('api PATCH "/repos/$REPO_SLUG/releases/$RELEASE_ID"', 'api POST "/repos/$REPO_SLUG/releases/$RELEASE_ID"'),
    script.replace('verify_signature "$TMPDIR_DL/$APK_NAME"', 'echo unchecked'),
):
    try:
        script_contract(variant)
    except AssertionError:
        continue
    raise AssertionError("Unsafe mutation escaped structural release regression check")
print("PASS local and CI release structural ordering, with three unsafe mutation controls")
