# PDF Split: formal model baseline and required quality gates

The \`tools/formal/verify_models.py\` command checks **six small bounded design abstractions**, each with a deliberately unsafe version that must violate an invariant. It also checks that the Kotlin and GitHub workflow paths used as source anchors still exist.

Run: \`python3 tools/formal/verify_models.py\`.

**This is not TLC or a proof about production Kotlin.** It does not establish that the source code implements the safe models. Current product code still has independently reported source-replacement, chapter-selection, cancellation-cleanup, and SAF naming gaps. The model checks run in CI to prevent the *specified design* from regressing while separate Android unit tests and device/fixture regressions are developed. Do not mark a product bug resolved from this command.

Pstack's \`/create-formal-verification-skill\` and \`/statechart\` method inspired the six skills under \`.cursor/skills/verify-pdf-*/\`. Their status is **bounded model verified; real-code binding incomplete**, not "fully formal-verified". Genuine TLC specs and pstack-generated statecharts must be checked before being promoted to a full formal release gate.

## Publish contract

GitHub Actions executes Android debug build, JVM tests, and lint. The publish job is opt-in on the master branch with a \`[publish]\` commit or manual dispatch, or on a matching version tag, after the verify job. The job requires signing secrets \`M1RACLE_RELEASE_STORE_BASE64\`, \`M1RACLE_RELEASE_STORE_PASSWORD\`, \`M1RACLE_RELEASE_KEY_ALIAS\`, \`M1RACLE_RELEASE_KEY_PASSWORD\` and compares the new signed APK to the previous public release's signer and versionCode.

Publish first uploads APK and SHA-256 into a **draft**. It downloads both assets, checks byte-for-byte identity, checks SHA-256 and signature, then makes the release public. Failed checks leave a draft instead of publicly distributing an unverified APK.

**Device acceptance is separate.** Before any manual publish, confirm all P1 fixes in real Android environments and independently check output PDF page ownership/content. A green CI alone is insufficient.

Baseline source commit: \`52517b655e8b3edcf74ac5e097400784012ca7f4\`. Move this baseline only after re-evaluating actual source behavior and updating models and tests.
