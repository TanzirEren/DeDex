# DeDex (com.de.dex)

Android app (Java + XML) that unpacks protected APKs, exports hidden DEX files,
scans them, and rebuilds a signed APK.

## Features
- Select APK, auto-detect MTENC protector, list plain and hidden DEX (magic-byte scan)
- Out: decrypt and extract all DEX (size and CRC verified)
- Export DEX to a folder, or save all as ZIP
- Security scan: hosts, risky API references, non-standard packages
- Build APK: restores the original Application class, removes the loader, signs (v1/v2/v3)

## Build with GitHub Actions
1. Create a new GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Push to `main`. Open the **Actions** tab, wait for "Build DeDex".
3. Download the artifact `DeDex-debug-apk` and install the APK.

You can also run it manually: Actions, Build DeDex, Run workflow.

## Notes
- Build APK works for MTENC-protected APKs. Other packers need their own reader.
- The rebuilt APK is signed with the bundled DeDex debug key, not the original developer's key.
  Use this for analysis and testing of apps you are allowed to modify.
