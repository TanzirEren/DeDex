# DeDex (com.de.dex)

Android app (Java + XML) that unpacks protected APKs, exports hidden DEX files,
scans them, and rebuilds a signed APK.

## Features
- Select APK, auto-detect MTENC protector, list plain and hidden DEX (magic-byte scan)
- Out: decrypt and extract all DEX (size and CRC verified)
- Export DEX to a folder, or save all as ZIP
- Security scan: hosts, risky API references, non-standard packages
- Build APK: restores the original Application class, removes the loader, signs (v1/v2/v3)

## Remote Unpack (new)
A screen inside the app that runs the `DeDex-MemoryDump` GitHub Actions
workflow for you and pulls the result back, for APKs where the protector
hides its logic in native code (ArkStub/VMP-style) and a local static
unpack isn't possible.

1. On GitHub, generate a **fine-grained PAT** (Settings → Developer
   settings → Personal access tokens → Fine-grained tokens). Repository
   access: the repo that holds the `DeDex-MemoryDump` workflow. Permissions:
   **Contents: Read and write** (to upload the APK as a release asset) and
   **Actions: Read and write** (to start the run and fetch its results).
2. In DeDex, open **Remote Unpack**, paste the token, owner, repo, the
   workflow file name (`memory-dump.yml` by default) and branch, tap
   **Save settings**.
3. **Select protected APK**, then **Fetch**. The app uploads it as a
   release asset, triggers the workflow, polls it (this takes several
   minutes — emulator boot + install + run), and on success downloads and
   unzips the `recovered-dex` artifact into the app's storage.
4. Recovered files land in the same place `Out` uses, so afterwards you
   can go back to the main screen and use **Security scan** / **Export
   DEX** / **Save ZIP** on them directly, or just tap **Save recovered DEX
   as ZIP** right there on the Remote Unpack screen.

The token is stored with `EncryptedSharedPreferences`, not plain text.

## Build with GitHub Actions
1. Create a new GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Push to `main`. Open the **Actions** tab, wait for "Build DeDex".
3. Download the artifact `DeDex-debug-apk` and install the APK.

You can also run it manually: Actions, Build DeDex, Run workflow.

## Notes
- Build APK works for MTENC-protected APKs. Other packers need their own reader.
- The rebuilt APK is signed with the bundled DeDex debug key, not the original developer's key.
  Use this for analysis and testing of apps you are allowed to modify.
- Remote Unpack's networking and artifact-handling logic is unit-tested against realistic GitHub
  API response shapes, and the app compiles cleanly against the Android SDK with these additions.
  The actual end-to-end run (emulator boot, root, memory scan on GitHub's servers) can only be
  confirmed by running it for real — if a step fails, the status box shows which one; paste it
  back and it'll get fixed directly.
