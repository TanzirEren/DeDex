package com.de.dex;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.de.dex.core.GitHubApi;
import com.de.dex.core.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class RemoteUnpackActivity extends Activity {
    private static final int RQ_PICK_APK = 1;
    private static final int RQ_SAVE_ZIP = 2;
    private static final int RUN_TIMEOUT_SECONDS = 20 * 60;

    private EditText etToken, etOwner, etRepo, etWorkflow, etBranch;
    private TextView tvApk, tvStatus;
    private Button btnFetch, btnSaveResult;
    private ProgressBar progress;

    private SecurePrefs prefs;
    private File apk;
    private File recoveredDir;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_remote);
        prefs = new SecurePrefs(this);
        recoveredDir = new File(getFilesDir(), "out"); // shared with MainActivity's Export/Scan buttons

        etToken = findViewById(R.id.etToken);
        etOwner = findViewById(R.id.etOwner);
        etRepo = findViewById(R.id.etRepo);
        etWorkflow = findViewById(R.id.etWorkflow);
        etBranch = findViewById(R.id.etBranch);
        tvApk = findViewById(R.id.tvApk);
        tvStatus = findViewById(R.id.tvStatus);
        progress = findViewById(R.id.progress);
        btnFetch = findViewById(R.id.btnFetch);
        btnSaveResult = findViewById(R.id.btnSaveResult);
        Button btnSaveSettings = findViewById(R.id.btnSaveSettings);
        Button btnPickApk = findViewById(R.id.btnPickApk);

        etToken.setText(prefs.token());
        etOwner.setText(prefs.owner());
        etRepo.setText(prefs.repo());
        etWorkflow.setText(prefs.workflow());
        etBranch.setText(prefs.branch());

        btnSaveSettings.setOnClickListener(v -> {
            prefs.save(etToken.getText().toString().trim(),
                    etOwner.getText().toString().trim(),
                    etRepo.getText().toString().trim(),
                    etWorkflow.getText().toString().trim(),
                    etBranch.getText().toString().trim());
            toast("Settings saved (encrypted on-device)");
        });

        btnPickApk.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, RQ_PICK_APK);
        });

        btnFetch.setOnClickListener(v -> startFetch());
        btnSaveResult.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, "recovered_dex.zip");
            startActivityForResult(i, RQ_SAVE_ZIP);
        });
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void busy(boolean on) {
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        btnFetch.setEnabled(!on);
    }

    private void status(String s) { ui.post(() -> tvStatus.setText(s)); }
    private void appendStatus(String line) {
        ui.post(() -> tvStatus.setText(tvStatus.getText() + "\n" + line));
    }

    private void startFetch() {
        if (!prefs.isConfigured()
                || etToken.getText().toString().trim().isEmpty()
                || etOwner.getText().toString().trim().isEmpty()
                || etRepo.getText().toString().trim().isEmpty()) {
            toast("Fill in token, owner and repo, then Save settings first");
            return;
        }
        if (apk == null) { toast("Select an APK first"); return; }

        final String token = etToken.getText().toString().trim();
        final String owner = etOwner.getText().toString().trim();
        final String repo = etRepo.getText().toString().trim();
        final String workflow = etWorkflow.getText().toString().trim();
        final String branch = etBranch.getText().toString().trim();

        busy(true);
        btnSaveResult.setVisibility(View.GONE);
        status("Starting ...");
        pool.execute(() -> {
            try {
                GitHubApi api = new GitHubApi(owner, repo, token);
                GitHubApi.Progress p = this::appendStatus;

                String assetUrl = api.uploadAsReleaseAsset(apk, "dedex", p);
                appendStatus("Uploaded. Asset url ready.");

                long before = api.latestRunId(workflow);

                Map<String, String> inputs = new LinkedHashMap<>();
                inputs.put("apk_url", assetUrl);
                inputs.put("settle_seconds", "15");
                api.dispatch(workflow, branch.isEmpty() ? "main" : branch, inputs);
                appendStatus("Dispatched. Waiting for GitHub to start the run ...");

                long runId = api.waitForNewRun(workflow, before, 60, p);
                if (runId == -1) {
                    appendStatus("Timed out waiting for the run to appear. Check the Actions tab on GitHub manually.");
                    return;
                }
                appendStatus("Run id " + runId + " started: https://github.com/" + owner + "/" + repo + "/actions/runs/" + runId);

                GitHubApi.RunState state = api.waitForCompletion(runId, RUN_TIMEOUT_SECONDS, p);
                if (!"completed".equals(state.status)) {
                    appendStatus("Timed out after " + (RUN_TIMEOUT_SECONDS / 60) + " min. Check it on GitHub: " + state.htmlUrl);
                    return;
                }
                appendStatus("Run finished: " + state.conclusion);
                if (!"success".equals(state.conclusion)) {
                    appendStatus("Workflow did not succeed. Pulling launch diagnostics ...");
                    showLaunchDiagnostics(api, runId, state.htmlUrl);
                    return;
                }

                List<GitHubApi.Artifact> arts = api.listArtifacts(runId);
                GitHubApi.Artifact dexArt = null, rawArt = null;
                for (GitHubApi.Artifact a : arts) {
                    if ("recovered-dex".equals(a.name)) dexArt = a;
                    if ("raw-memory-regions".equals(a.name)) rawArt = a;
                }
                if (dexArt == null || dexArt.sizeBytes == 0) {
                    appendStatus("No DEX recovered. "
                            + (rawArt != null ? "Raw memory dumps are on the run (" + Util.human(rawArt.sizeBytes) + ") for manual inspection." : "")
                            + "\nOpen: " + state.htmlUrl);
                    return;
                }

                appendStatus("Downloading recovered-dex (" + Util.human(dexArt.sizeBytes) + ") ...");
                File zip = new File(getCacheDir(), "recovered_dex.zip");
                api.downloadArtifactZip(dexArt.id, zip);

                if (recoveredDir.exists()) {
                    File[] old = recoveredDir.listFiles();
                    if (old != null) for (File f : old) if (f.getName().endsWith(".dex")) f.delete();
                }
                recoveredDir.mkdirs();
                int n = unzipDexInto(zip, recoveredDir);
                zip.delete();

                appendStatus(n + " DEX file(s) saved. You can now use Save ZIP below, or go back and use Security scan / Export DEX on the main screen.");
                ui.post(() -> btnSaveResult.setVisibility(View.VISIBLE));
            } catch (Exception e) {
                appendStatus("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                ui.post(() -> busy(false));
            }
        });
    }

    private static final String[] CRASH_MARKERS = {
            "fatal exception", "androidruntime", "fatal signal", "has died",
            "libarkstub", "arkvmp", "segv", "signal 11"
    };

    /** Downloads the 'launch-diagnostics' artifact (if any) and shows the lines most likely to explain a failed launch. */
    private void showLaunchDiagnostics(GitHubApi api, long runId, String htmlUrl) {
        try {
            GitHubApi.Artifact diag = null;
            for (GitHubApi.Artifact a : api.listArtifacts(runId)) {
                if ("launch-diagnostics".equals(a.name)) diag = a;
            }
            if (diag == null) {
                appendStatus("No diagnostics artifact was produced. Open " + htmlUrl + " and check the job log directly.");
                return;
            }
            File zip = new File(getCacheDir(), "diag.zip");
            api.downloadArtifactZip(diag.id, zip);
            File dir = new File(getCacheDir(), "diag");
            if (dir.exists()) { File[] old = dir.listFiles(); if (old != null) for (File f : old) f.delete(); }
            dir.mkdirs();
            try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new FileInputStream(zip))) {
                java.util.zip.ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    File out = new File(dir, new File(e.getName()).getName());
                    try (OutputStream o = new FileOutputStream(out)) { Util.copy(zin, o); }
                }
            }
            zip.delete();

            File logcat = new File(dir, "full_logcat.txt");
            File monkey = new File(dir, "monkey_output.txt");
            StringBuilder out = new StringBuilder();
            if (monkey.isFile()) {
                String m = readSmall(monkey);
                if (m.toLowerCase().contains("aborted") || m.toLowerCase().contains("no activities")) {
                    out.append("Launcher problem (from monkey):\n").append(lastLines(m, 10)).append("\n\n");
                }
            }
            if (logcat.isFile()) {
                String log = readSmall(logcat);
                String hits = grepMarkers(log, CRASH_MARKERS, 40);
                out.append(hits.isEmpty()
                        ? "No crash/FATAL/signal lines found in logcat either -- this points to a silent exit "
                          + "(e.g. the app detecting the emulator and quietly closing itself) rather than a crash."
                        : "Matching lines from the device log:\n" + hits);
            } else {
                out.append("No full_logcat.txt in the artifact.");
            }
            appendStatus(out.toString());
            appendStatus("\nFull files saved on GitHub as the 'launch-diagnostics' artifact on this run: " + htmlUrl);
        } catch (Exception e) {
            appendStatus("Could not fetch diagnostics (" + e.getMessage() + "). Open " + htmlUrl + " directly.");
        }
    }

    private static String readSmall(File f) throws Exception {
        long len = Math.min(f.length(), 2L * 1024 * 1024); // cap at 2MB for in-app display
        byte[] buf = new byte[(int) len];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
        }
        return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String lastLines(String text, int n) {
        String[] lines = text.split("\n");
        int start = Math.max(0, lines.length - n);
        StringBuilder b = new StringBuilder();
        for (int i = start; i < lines.length; i++) b.append(lines[i]).append('\n');
        return b.toString();
    }

    private static String grepMarkers(String text, String[] markers, int maxLines) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (String line : text.split("\n")) {
            String low = line.toLowerCase();
            for (String m : markers) {
                if (low.contains(m)) {
                    b.append(line).append('\n');
                    n++;
                    break;
                }
            }
            if (n >= maxLines) { b.append("  ... (truncated, see full artifact)\n"); break; }
        }
        return b.toString();
    }

    private static int unzipDexInto(File zipFile, File outDir) throws Exception {
        int n = 0;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory() || !e.getName().endsWith(".dex")) continue;
                File out = new File(outDir, new File(e.getName()).getName());
                try (OutputStream o = new FileOutputStream(out)) { Util.copy(zin, o); }
                n++;
            }
        }
        return n;
    }

    @Override
    protected void onActivityResult(int rq, int res, Intent data) {
        super.onActivityResult(rq, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        ContentResolver cr = getContentResolver();
        if (rq == RQ_PICK_APK) {
            pool.execute(() -> {
                try {
                    String name = queryName(cr, uri);
                    File dst = new File(getCacheDir(), "remote_input.apk");
                    try (InputStream in = cr.openInputStream(uri); OutputStream o = new FileOutputStream(dst)) {
                        Util.copy(in, o);
                    }
                    apk = dst;
                    ui.post(() -> tvApk.setText(name + "\n" + Util.human(dst.length())));
                } catch (Exception e) {
                    ui.post(() -> toast("Could not read file: " + e.getMessage()));
                }
            });
        } else if (rq == RQ_SAVE_ZIP) {
            pool.execute(() -> {
                try (OutputStream o = cr.openOutputStream(uri); ZipOutputStream zo = new ZipOutputStream(o)) {
                    File[] fs = recoveredDir.listFiles();
                    if (fs != null) {
                        for (File f : fs) {
                            if (!f.getName().endsWith(".dex")) continue;
                            zo.putNextEntry(new ZipEntry(f.getName()));
                            try (InputStream in = new FileInputStream(f)) { Util.copy(in, zo); }
                            zo.closeEntry();
                        }
                    }
                    ui.post(() -> toast("Saved"));
                } catch (Exception e) {
                    ui.post(() -> toast("Save failed: " + e.getMessage()));
                }
            });
        }
    }

    private static String queryName(ContentResolver cr, Uri uri) {
        try (android.database.Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) { }
        String p = uri.getLastPathSegment();
        return p == null ? "app.apk" : p;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
