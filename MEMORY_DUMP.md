# DeDex Memory Dump workflow

Pulls the decrypted DEX straight out of RAM for APKs where the protector
hides the real key/logic in native code (ArkStub / VMP-style protectors),
using a rooted GitHub-hosted emulator. No phone, no root, no data loss.

## How it works
1. Boots a headless Android 15 (API 35) emulator with ARM translation
   (so arm64 `.so` libraries still run on the x86_64 GitHub runner).
2. Installs your APK, launches it, waits for the loader to run.
3. Roots the emulator (`adb root` — this is a plain emulator image, not a
   real device, so this is always allowed, no unlocking needed).
4. Reads `/proc/<pid>/maps`, dumps every readable anonymous/heap region.
5. Downloads those dumps to the runner, scans them for the DEX file magic
   (`dex\n...`), and slices out anything with a structurally valid header.

## Use it (any APK, no size limit from GitHub's web/git upload)
APKs over ~25MB can't go through GitHub's web uploader, and ones over
100MB can't go through a normal `git push` either. GitHub Releases don't
have that problem (2GB per file), so that's how you get the APK in:

1. One-time: generate a **fine-grained PAT** at
   github.com → Settings → Developer settings → Personal access tokens →
   Fine-grained tokens. Repository access: this repo only. Permissions:
   **Contents → Read and write**. Nothing else.
2. Upload the APK as a release asset (works from Termux, PC, anywhere
   `curl` exists — not from your browser, so no size limit bites):
   ```
   export GH_PAT="github_pat_xxxxxxxx"
   bash scripts/upload_apk_release.sh yourname/DeDex /path/to/app.apk
   ```
   This prints a `browser_download_url` at the end.
3. Go to **Actions → DeDex Memory Dump → Run workflow**, paste that URL
   into the **apk_url** box, and run it.
4. For the *next* APK, repeat steps 2–3 with the new file — no need to
   touch the repo itself. (The old way — committing an `.apk` straight
   into the repo — still works too, as a fallback, if you leave `apk_url`
   empty and the file is under 100MB.)
5. When it finishes, open the run and download:
   - **recovered-dex** — any DEX files it managed to slice out. Start here.
   - **raw-memory-regions** — the raw dumps + `full_maps.txt`, in case
     `recovered-dex` is empty and you want to look by hand (e.g. with a
     hex editor, or by re-running the extractor with looser checks).

## If `recovered-dex` is empty
Open the job log for the "Run emulator and dump memory" step and check for:
- `"Could not get root"` → the image used isn't rootable; make sure the
  workflow is still using a non-Play Store `target: google_apis` image.
- `"App is not running (pidof empty)"` → the app crashed or detected the
  emulator and exited. The tail of `adb logcat` is printed right there —
  paste it back to me and I'll adjust the script.
- No errors, but 0 DEX recovered → the real DEX may be split across two
  dumped regions (the extractor currently requires it to sit fully inside
  one `region_*.bin`), or it decrypts later than `SETTLE_SECONDS`. Try
  re-running with a larger `settle_seconds` input (e.g. `30`), and if it's
  still empty, download `raw-memory-regions` and send me `full_maps.txt`
  plus the total dump size so I can tune the script.

## Honesty note
I could not run an actual Android emulator inside my own sandbox to test
this end-to-end (no KVM / nested virtualization available there), so this
has only been verified piece by piece:
- the DEX-slicing script (`extract_dex_from_dumps.py`) is tested and
  confirmed to correctly pull a real DEX out of a synthetic memory dump;
- the `adb`/`dd`/`/proc/<pid>/mem` commands are standard, well-documented
  techniques, but the emulator boot, root, and exact timing can only be
  confirmed by actually running the workflow. Paste me the Actions log if
  anything fails and I'll fix the script directly.
