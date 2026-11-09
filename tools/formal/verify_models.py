#!/usr/bin/env python3
"""Six BOUNDED DESIGN models, not a proof of Android/Kotlin behavior.

Reproducible, network-free checks of proposed invariants and deliberate broken
variants. Existing product defects are *not* marked repaired by a model pass.
Run Kotlin tests and emulator/real-file checks separately.
"""
from __future__ import annotations
from itertools import permutations, product
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

ANCHORS = {
    "source": ("src/main/java/com/m1racle/reader/pdfsplit/ui/SplitViewModel.kt", ("fun onSourcePicked(", "fun start(")),
    "outline": ("src/main/java/com/m1racle/reader/pdfsplit/split/OutlinePlanner.kt", ("fun plan(", "startPage1")),
    "auto": ("src/main/java/com/m1racle/reader/pdfsplit/split/FastPathGuard.kt", ("fun xrefVeto(", "scanDrawings(")),
    "split": ("src/main/java/com/m1racle/reader/pdfsplit/split/PdfRangeSplitter.kt", ("suspend fun split(", "deleteDocument")),
    "output": ("src/main/java/com/m1racle/reader/pdfsplit/split/SafOutputDirectory.kt", ("fun listExistingNames(",)),
    "release": (".github/workflows/android-verify-publish.yml", ("--draft", "gh release download", "gh release edit")),
}

def source(safe: bool) -> list[str]:
    issues = []
    for order in permutations(("A", "B")):
        latest, visible, rules = "B", "", ""
        for completed in order:
            if safe and completed != latest:
                continue
            visible = completed
            rules = completed if safe or rules == "" else rules
        if visible != latest or rules != latest:
            issues.append("old asynchronous file load replaced newer selection: " + ",".join(order))
    return issues

def outline(safe: bool) -> list[str]:
    starts = (1, 6, 12, 21)
    issues = []
    for mask in range(1, 8):
        chosen = [i for i in range(3) if mask & (1 << i)]
        for j, idx in enumerate(chosen):
            end = starts[idx + 1] - 1 if safe else (starts[chosen[j + 1]] - 1 if j + 1 < len(chosen) else 20)
            if end >= starts[idx + 1]:
                issues.append("unselected chapter leaked into selected range: mask=%d chapter=%d" % (mask, idx))
    return issues

def auto(safe: bool) -> list[str]:
    issues = []
    for trusted, complete, missing_painted in product((False, True), repeat=3):
        strict = trusted and (complete and not missing_painted if safe else True)
        if strict and not (trusted and complete and not missing_painted):
            issues.append("strict parser accepted incomplete drawing evidence")
    return issues

def split(safe: bool) -> list[str]:
    issues = []
    for created, delete_ok in product(range(3), (False, True)):
        deleted = created if delete_ok else 0
        phase = "cleanup-error" if safe and not delete_ok and created > 0 else "cancelled"
        if phase == "cancelled" and deleted != created:
            issues.append("cancelled despite orphaned SAF files: created=%d" % created)
    return issues

def output(safe: bool) -> list[str]:
    issues = []
    for listing_ok, provider_renames in product((False, True), repeat=2):
        attempted_write = listing_ok if safe else True
        planned, actual = "chapter.pdf", "chapter (1).pdf" if provider_renames else "chapter.pdf"
        displayed = actual if safe else planned
        if attempted_write and not listing_ok:
            issues.append("write proceeded despite failed directory listing")
        if attempted_write and displayed != actual:
            issues.append("UI filename does not match SAF actual filename")
    return issues

def release(safe: bool) -> list[str]:
    issues = []
    for assets_uploaded, readback_ok in product((False, True), repeat=2):
        publicly_visible = assets_uploaded and readback_ok if safe else True
        if publicly_visible and not (assets_uploaded and readback_ok):
            issues.append("public Release visible before remote assets are verified")
    return issues

MODELS = {
    "source": source, "outline": outline, "auto": auto,
    "split": split, "output": output, "release": release,
}

def main() -> None:
    # Fail closed when the anchor files are renamed or disappear. This is only a
    # synchronization check, not a claim that their semantics match the model.
    for model, (path, markers) in ANCHORS.items():
        data = (ROOT / path).read_text(encoding="utf-8")
        for marker in markers:
            if marker not in data:
                raise AssertionError("%s: source mapping drifted (%s: %s)" % (model, path, marker))
    for name, check in MODELS.items():
        good, bad = check(True), check(False)
        if good:
            raise AssertionError("%s: hardened design violates its invariant: %s" % (name, good))
        if not bad:
            raise AssertionError("%s: deliberately broken design escaped detection" % name)
        print("PASS %-8s design invariant; negative control caught %d cases" % (name, len(bad)))
    print("PASS: six bounded design models + six negative controls + source-path anchors.")
    print("LIMIT: does NOT prove production Kotlin, SAF providers, Android lifecycle, or real PDF output.")
    print("KNOWN LIVE GAPS: source/outline/split/output remain open until Kotlin and device regression evidence.")

if __name__ == "__main__":
    main()
