---
name: verify-pdf-outline
description: "Check PDF Split chapter ownership safety model when touching src/main/java/com/m1racle/reader/pdfsplit/split/OutlinePlanner.kt"
---

# Chapter ownership

Mapped source: `src/main/java/com/m1racle/reader/pdfsplit/split/OutlinePlanner.kt`. Last synchronized source reference: `52517b655e8b3edcf74ac5e097400784012ca7f4`.

Model: `formal/pdf/specs/PdfOutlineSelection.tla`. Configuration: `formal/pdf/specs/PdfOutlineSelection.cfg`. Check from project root: `bash formal/pdf/verify_tlc.sh`. This runs TLC on six models and checks six intentionally defective variants. Also run `python3 tools/formal/verify_models.py` for the independent Python bounded abstractions.

Read the model's invariant and compare every action with actual Kotlin or release workflow statements. The model is a **design**; a green check does not establish correspondence to Kotlin. Current Kotlin uses next selected rather than next actual chapter anchor.

For user flow diagrams use Cursor pstack `/statechart` on the TLA+ model and its view in `formal/pdf/views/` where present; CI runs the official renderer and uploads charts. After product changes, repeat the Kotlin tests, device regressions and model checks before advancing the synchronized code commit. Use `/maintain-formal-verification-skill` after changes that invalidate the mapping.
