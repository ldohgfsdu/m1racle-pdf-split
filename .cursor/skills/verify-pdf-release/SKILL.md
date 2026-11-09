---
name: verify-pdf-release
description: "Check PDF Split release publication safety model when touching .github/workflows/android-verify-publish.yml"
---

# Release publication

Mapped source: `.github/workflows/android-verify-publish.yml`. Last synchronized source reference: `52517b655e8b3edcf74ac5e097400784012ca7f4`.

Model: `formal/pdf/specs/PdfReleasePublication.tla`. Configuration: `formal/pdf/specs/PdfReleasePublication.cfg`. Check from project root: `bash formal/pdf/verify_tlc.sh`. This runs TLC on six models and checks six intentionally defective variants. Also run `python3 tools/formal/verify_models.py` for the independent Python bounded abstractions.

Read the model's invariant and compare every action with actual Kotlin or release workflow statements. The model is a **design**; a green check does not establish correspondence to Kotlin. A real tagged draft publication has not yet been exercised.

For user flow diagrams use Cursor pstack `/statechart` on the TLA+ model and its view in `formal/pdf/views/` where present; CI runs the official renderer and uploads charts. After product changes, repeat the Kotlin tests, device regressions and model checks before advancing the synchronized code commit. Use `/maintain-formal-verification-skill` after changes that invalidate the mapping.
