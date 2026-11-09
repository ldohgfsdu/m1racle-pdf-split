# PDF Split: bounded formal specifications and statecharts

This directory contains six small TLA+ state machines, each with a safe design and an intentionally unsafe alternative selected by `Correct = TRUE/FALSE` in its TLC config.

`bash formal/pdf/verify_tlc.sh` executes TLC v1.8.0 (SHA-1 pinned) over the safe models and proves each negative-control config produces a violated invariant. This is now a **real TLC model-checker run**, unlike the separately available Python bounded design checks.

Subsytems: PdfSourceSession, PdfOutlineSelection, PdfAutoParser, PdfSplitCleanup, PdfOutputNaming, PdfReleasePublication. Bounds: two file selections, three distinct chapter anchors, two output parts, and finite success/failure stages. Not modeled: Android process death, unbounded concurrency, provider behavior, PDFBox's entire PDF grammar.

The CI workflow also runs the upstream Cursor pstack `/statechart` command for the main source-switch, chapter-selection, split-cleanup and release-publication user flows, using the four `views/*.ts` reader-facing maps. The generated chart/report files are uploaded as GitHub Actions artifacts. These charts are TLC-derived; do not substitute the older hand-authored sketches for checked charts.

**Critical limitation:** These TLA+ specifications describe the desired *safe design*, not an extraction from current Kotlin code. The existing Kotlin source still requires P1 fixes and independently verified Android device/fixture regressions. A model PASS does not mean a production defect is fixed. For each changed Kotlin subsystem, compare its actual transitions with the relevant TLA+ actions and rerun both JVM and device tests.

Build baseline: `52517b655e8b3edcf74ac5e097400784012ca7f4`; advance synchronized code SHA only after real-code review and test evidence.
