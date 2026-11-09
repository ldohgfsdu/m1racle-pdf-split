# PDF Split formal checks and release gates

In CI:
1. Android API 36 stable hosted SDK, JDK 17, Gradle JVM tests, debug APK and lintDebug (lint findings remain advisory while pre-existing abortOnError=false).
2. `python3 tools/formal/verify_models.py`: six network-free bounded design checks with six negative controls and source mapping anchors.
3. `bash formal/pdf/verify_tlc.sh`: actual TLC 1.8.0 model checking of six TLA+ safe design models and six deliberately unsafe controls.
4. Upstream pstack `/statechart`: TLC-derived charts for four important user flows, saved as CI artifacts.
5. Opt-in release workflow only after all above verification succeeds. Release remains a **draft** until APK and SHA-256 are downloaded again and signer/hash checks pass.

Details and limits: `formal/pdf/README.md`.

**The model pass is NOT a Kotlin correctness proof.** Source isolation, chapter ownership, cancellation cleanup and SAF naming have known P1/P2 gaps in the codebase. Formal designs are not evidence those implementation gaps were fixed. A real Android device run with 20-page, 400-page and 988 MB inputs must be completed before publication.

Four GitHub signing secrets required to enable publishing (not provisioned by this PR): `M1RACLE_RELEASE_STORE_BASE64`, `M1RACLE_RELEASE_STORE_PASSWORD`, `M1RACLE_RELEASE_KEY_ALIAS`, `M1RACLE_RELEASE_KEY_PASSWORD`. Local `scripts/release.sh` is left unchanged pending a verified hosted release.

Source baseline: `52517b655e8b3edcf74ac5e097400784012ca7f4`.
