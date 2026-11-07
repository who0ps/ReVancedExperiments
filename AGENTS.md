# Instructions for coding agents

## Project and build

- This repository contains Kotlin ReVanced patches. Preserve the existing patch DSL, package layout, and local utility conventions.
- Build the patch bundle with `./gradlew clean :patches:buildAndroid --no-daemon`.
- A successful Gradle build proves the patch bundle compiles; it does **not** prove that a patch matches or works in a Telegram APK. Runtime patching must be verified against the target APK before claiming success.

## Telegram version matching

- Use the official Android source at [DrKLO/Telegram](https://github.com/DrKLO/Telegram) to understand intended behavior.
- Before relying on a source checkout, verify its version in the root `gradle.properties` (`APP_VERSION_NAME`). At the time this file was added, upstream `master` reported `13.2.1`.
- Use source code to understand the logical operation, but use the exact target APK's DEX as the authority for R8-obfuscated class names, method names, descriptors, registers, and control flow.
- Do not infer DEX register assignments from source locals, and do not assume a neighboring instruction is the intended value. Inspect try/catch paths and merge points.
- The extracted APK used for the Telegram 13.2.1 investigation is in [who0ps/temp](https://github.com/who0ps/temp), under `base/classes*.dex`.

## Telegram 13.2.1 findings

- **Bypass Integrity:** upstream source calls `AndroidUtilities.getCertificateSHA256Fingerprint()` from `ConnectionsManager`. The helper obtains the signing certificate, parses it as X.509, hashes its encoded bytes with SHA-256, and converts the digest to hex. In the inspected release DEX this helper is inlined into `ConnectionsManager.<init>(I)V`. The hex result is copied to a shared local after a try/catch merge; the catch path writes the empty-string fallback to that same local. Find the local by following the `Utilities` byte-array-to-string result into the merge, rather than by checking the instruction immediately before `TimeZone.getDefault()`.
- **Disable Auto Update:** the source update gate is in `LaunchActivity.onResume()`, where `SharedConfig.pendingAppUpdate` and `can_not_skip` control the blocking update screen. Suppress the pending update at that gate; do not return early from the whole `onResume()`.
- **Download Speed Boost:** the inspected DEX maps the file operation parameter update to `org.telegram.messenger.g7.t()V`. Its chunk-size/concurrency fields are obfuscated, so verify field descriptors and story/preload behavior against DEX before changing values.
- **Hide sponsored ads:** locate the relevant controller method using its `TL_messages_getSponsoredMessages` request reference rather than assuming the source method name survives R8.

These are version-specific observations. Re-inspect source and DEX when the Telegram version changes.

## Release hygiene

- The release configuration uses a glob over `patches/build/libs/patches-*.rvp`. Old versioned files left in that directory can be uploaded alongside the current bundle.
- Remove stale `.rvp` outputs before building, and fail the workflow unless exactly one versioned `.rvp` is present.
- In the ReVanced Manager bundle JSON, select `patches-${TAG#v}.rvp` by exact name. Never select the first asset whose name ends in `.rvp`.
- After publishing, verify the release contains the expected bundle and that `patches-bundle.json` points to that exact asset. Check the uploaded asset's SHA-256 when reporting the build.
