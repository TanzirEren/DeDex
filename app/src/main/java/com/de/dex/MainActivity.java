package com.de.dex;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.de.dex.core.ApkRebuilder;
import com.de.dex.core.ApkScanner;
import com.de.dex.core.Extractor;
import com.de.dex.core.SecurityScan;
import com.de.dex.core.Signer;
import com.de.dex.core.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private static final int RQ_PICK = 1;
    private static final int RQ_TREE = 2;
    private static final int RQ_SAVE_APK = 3;
    private static final int RQ_SAVE_ZIP = 4;

    private interface Job { String run() throws Exception; }

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView tvFile, tvLog;
    private ProgressBar progress;
    private Button[] actionButtons;

    private File apk;
    private File outDir;
    private File builtApk;
    private ApkScanner.Info info;
    private String apkName = "app";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        outDir = new File(getFilesDir(), "out");
        tvFile = findViewById(R.id.tvFile);
        tvLog = findViewById(R.id.tvLog);
        progress = findViewById(R.id.progress);
        Button pick = findViewById(R.id.btnPick);
        Button out = findViewById(R.id.btnOut);
        Button scan = findViewById(R.id.btnScan);
        Button build = findViewById(R.id.btnBuild);
        Button export = findViewById(R.id.btnExport);
        Button zip = findViewById(R.id.btnZip);
        actionButtons = new Button[]{pick, out, scan, build, export, zip};

        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, RQ_PICK);
        });
        out.setOnClickListener(v -> doOut());
        scan.setOnClickListener(v -> doScan());
        build.setOnClickListener(v -> doBuild());
        export.setOnClickListener(v -> {
            if (!haveDex()) { toast("Run Out first"); return; }
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), RQ_TREE);
        });
        zip.setOnClickListener(v -> {
            if (!haveDex()) { toast("Run Out first"); return; }
            saveAs("application/zip", apkName + "_dex.zip", RQ_SAVE_ZIP);
        });
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void log(String s) { tvLog.setText(s); }

    private void busy(boolean on) {
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        for (Button x : actionButtons) x.setEnabled(!on);
    }

    private void run(String title, Job job) {
        busy(true);
        log(title + " ...");
        pool.execute(() -> {
            String res;
            try {
                res = job.run();
            } catch (Throwable t) {
                res = "ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String text = res;
            ui.post(() -> { busy(false); log(text); });
        });
    }

    private boolean haveApk() {
        if (apk == null || info == null) { toast("Select an APK first"); return false; }
        return true;
    }

    private boolean haveDex() {
        File[] f = outDir.listFiles();
        return f != null && f.length > 0;
    }

    private List<File> dexFiles(boolean skipLoader) {
        List<File> l = new ArrayList<>();
        File[] f = outDir.listFiles();
        if (f != null) {
            for (File x : f) {
                if (!x.getName().endsWith(".dex")) continue;
                if (skipLoader && x.getName().startsWith("loader_")) continue;
                l.add(x);
            }
        }
        return l;
    }

    private void doOut() {
        if (!haveApk()) return;
        run("Extracting", () -> {
            final StringBuilder sb = new StringBuilder();
            List<File> made = Extractor.extractAll(apk, outDir, info, s -> sb.append(s).append('\n'));
            sb.append('\n').append(made.size()).append(" DEX file(s) ready.\nUse Export DEX, Save ZIP, Security scan or Build APK.");
            return sb.toString();
        });
    }

    private void doScan() {
        if (!haveApk()) return;
        run("Scanning", () -> {
            if (!haveDex()) Extractor.extractAll(apk, outDir, info, s -> { });
            return SecurityScan.run(dexFiles(true));
        });
    }

    private void doBuild() {
        if (!haveApk()) return;
        if (info.mtenc == null) { toast("Build APK supports MTENC-protected APKs only"); return; }
        run("Building APK", () -> {
            if (!haveDex()) Extractor.extractAll(apk, outDir, info, s -> { });
            File unsigned = new File(getCacheDir(), "unsigned.apk");
            builtApk = new File(getCacheDir(), "rebuilt_signed.apk");
            String rep = ApkRebuilder.rebuild(apk, outDir, info.mtenc, unsigned);
            try (InputStream ks = getAssets().open("dedex.p12")) {
                Signer.sign(ks, unsigned, builtApk);
            }
            unsigned.delete();
            final String msg = rep + "\nSigned with the bundled DeDex debug key (v1+v2+v3).\nSize: "
                    + Util.human(builtApk.length()) + "\nChoose where to save it...";
            ui.post(() -> saveAs("application/vnd.android.package-archive", apkName + "_dedex.apk", RQ_SAVE_APK));
            return msg;
        });
    }

    private void saveAs(String mime, String name, int rq) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, rq);
    }

    @Override
    protected void onActivityResult(int rq, int res, Intent data) {
        super.onActivityResult(rq, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final ContentResolver cr = getContentResolver();
        switch (rq) {
            case RQ_PICK:
                run("Reading APK", () -> {
                    apkName = baseName(queryName(cr, uri));
                    File dst = new File(getCacheDir(), "input.apk");
                    try (InputStream in = cr.openInputStream(uri); OutputStream o = new java.io.FileOutputStream(dst)) {
                        Util.copy(in, o);
                    }
                    apk = dst;
                    builtApk = null;
                    info = ApkScanner.scan(dst);
                    final String label = queryName(cr, uri) + "\n" + Util.human(dst.length());
                    ui.post(() -> tvFile.setText(label));
                    File[] old = outDir.listFiles();
                    if (old != null) for (File f : old) f.delete();
                    return info.text + "\nTap Out to extract all DEX.";
                });
                break;
            case RQ_TREE:
                run("Exporting DEX", () -> {
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
                    int n = 0;
                    for (File f : dexFiles(false)) {
                        Uri d = DocumentsContract.createDocument(cr, doc, "application/octet-stream", f.getName());
                        if (d == null) continue;
                        try (InputStream in = new FileInputStream(f); OutputStream o = cr.openOutputStream(d)) {
                            Util.copy(in, o);
                        }
                        n++;
                    }
                    return "Exported " + n + " file(s) to the chosen folder.";
                });
                break;
            case RQ_SAVE_APK:
                run("Saving APK", () -> {
                    try (InputStream in = new FileInputStream(builtApk); OutputStream o = cr.openOutputStream(uri)) {
                        Util.copy(in, o);
                    }
                    return "Saved: " + queryName(cr, uri) + "\nInstall it only if you trust the source APK.";
                });
                break;
            case RQ_SAVE_ZIP:
                run("Saving ZIP", () -> {
                    try (OutputStream o = cr.openOutputStream(uri); ZipOutputStream zo = new ZipOutputStream(o)) {
                        for (File f : dexFiles(false)) {
                            zo.putNextEntry(new ZipEntry(f.getName()));
                            try (InputStream in = new FileInputStream(f)) { Util.copy(in, zo); }
                            zo.closeEntry();
                        }
                    }
                    return "Saved: " + queryName(cr, uri);
                });
                break;
            default:
                break;
        }
    }

    private static String queryName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {
            // fall through
        }
        String p = uri.getLastPathSegment();
        return p == null ? "app.apk" : p;
    }

    private static String baseName(String n) {
        int d = n.lastIndexOf('.');
        String b = d > 0 ? n.substring(0, d) : n;
        return b.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
