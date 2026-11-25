package com.de.dex.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Minimal GitHub REST API client: upload a file as a Release asset, trigger a
 * workflow_dispatch run, poll it to completion, then locate and download one
 * of its artifacts. No external HTTP library; plain HttpURLConnection so the
 * app has no extra runtime dependency beyond apksig and security-crypto.
 */
public final class GitHubApi {
    public interface Progress { void update(String line); }

    private final String owner, repo, token;

    public GitHubApi(String owner, String repo, String token) {
        this.owner = owner;
        this.repo = repo;
        this.token = token;
    }

    private HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Util.copy(in, bo);
        return bo.toString("UTF-8");
    }

    private static String body(HttpURLConnection c) throws IOException {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        return in == null ? "" : readAll(in);
    }

    private static void failIfError(HttpURLConnection c, String what) throws IOException {
        int code = c.getResponseCode();
        if (code >= 400) {
            throw new IOException(what + " failed (HTTP " + code + "): " + trim(body(c)));
        }
    }

    private static String trim(String s) {
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) { return (List<Object>) o; }

    // ---------------------------------------------------------------- upload

    /** Creates a new Release and uploads the file as its asset. Returns the API asset URL to use as apk_url later. */
    public String uploadAsReleaseAsset(File file, String tagPrefix, Progress p) throws Exception {
        String tag = tagPrefix + "-" + System.currentTimeMillis();
        String name = file.getName();

        p.update("Creating release " + tag + " ...");
        String reqBody = "{\"tag_name\":" + JsonWrite.str(tag)
                + ",\"name\":" + JsonWrite.str(name)
                + ",\"body\":\"Uploaded by DeDex for remote unpack\"}";
        HttpURLConnection c = open("https://api.github.com/repos/" + owner + "/" + repo + "/releases", "POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] b = reqBody.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(b.length);
        try (OutputStream o = c.getOutputStream()) { o.write(b); }
        failIfError(c, "Create release");
        Map<String, Object> rel = obj(Json.parse(body(c)));
        long relId = ((Number) rel.get("id")).longValue();
        c.disconnect();

        p.update("Uploading " + name + " (" + Util.human(file.length()) + ") ...");
        String uploadUrl = "https://uploads.github.com/repos/" + owner + "/" + repo
                + "/releases/" + relId + "/assets?name=" + urlEncode(name);
        HttpURLConnection u = open(uploadUrl, "POST");
        u.setDoOutput(true);
        // The release-asset endpoint needs a real Content-Length (not chunked
        // transfer-encoding) so it can hand the upload off to blob storage.
        u.setFixedLengthStreamingMode(file.length());
        u.setRequestProperty("Content-Type", "application/vnd.android.package-archive");
        try (OutputStream o = u.getOutputStream(); InputStream in = new FileInputStream(file)) {
            Util.copy(in, o);
        }
        failIfError(u, "Upload asset");
        Map<String, Object> asset = obj(Json.parse(body(u)));
        u.disconnect();
        return (String) asset.get("url"); // API url; works with Accept: application/octet-stream to fetch bytes
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ------------------------------------------------------------- dispatch

    /** Returns the run id of the latest run of this workflow right now, or -1 if none. */
    public long latestRunId(String workflowFile) throws Exception {
        HttpURLConnection c = open("https://api.github.com/repos/" + owner + "/" + repo
                + "/actions/workflows/" + urlEncode(workflowFile) + "/runs?per_page=1", "GET");
        failIfError(c, "List runs");
        Map<String, Object> r = obj(Json.parse(body(c)));
        c.disconnect();
        List<Object> runs = list(r.get("workflow_runs"));
        if (runs.isEmpty()) return -1;
        return ((Number) obj(runs.get(0)).get("id")).longValue();
    }

    public void dispatch(String workflowFile, String ref, Map<String, String> inputs) throws Exception {
        StringBuilder in = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : inputs.entrySet()) {
            if (!first) in.append(',');
            first = false;
            in.append(JsonWrite.str(e.getKey())).append(':').append(JsonWrite.str(e.getValue()));
        }
        in.append('}');
        String reqBody = "{\"ref\":" + JsonWrite.str(ref) + ",\"inputs\":" + in + "}";

        HttpURLConnection c = open("https://api.github.com/repos/" + owner + "/" + repo
                + "/actions/workflows/" + urlEncode(workflowFile) + "/dispatches", "POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] b = reqBody.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(b.length);
        try (OutputStream o = c.getOutputStream()) { o.write(b); }
        failIfError(c, "Dispatch workflow");
        c.disconnect();
    }

    /** Polls until a run newer than beforeRunId appears, or times out. Returns its id, or -1. */
    public long waitForNewRun(String workflowFile, long beforeRunId, int timeoutSeconds, Progress p) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            long id = latestRunId(workflowFile);
            if (id != -1 && id != beforeRunId) return id;
            p.update("Waiting for the run to appear on GitHub ...");
            Thread.sleep(4000);
        }
        return -1;
    }

    public static final class RunState {
        public String status;      // queued, in_progress, completed
        public String conclusion;  // success, failure, cancelled, null while running
        public String htmlUrl;
    }

    public RunState runState(long runId) throws Exception {
        HttpURLConnection c = open("https://api.github.com/repos/" + owner + "/" + repo
                + "/actions/runs/" + runId, "GET");
        failIfError(c, "Get run");
        Map<String, Object> r = obj(Json.parse(body(c)));
        c.disconnect();
        RunState s = new RunState();
        s.status = (String) r.get("status");
        s.conclusion = (String) r.get("conclusion");
        s.htmlUrl = (String) r.get("html_url");
        return s;
    }

    public RunState waitForCompletion(long runId, int timeoutSeconds, Progress p) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        RunState s;
        do {
            s = runState(runId);
            p.update("Run " + runId + ": " + s.status + (s.conclusion != null ? " (" + s.conclusion + ")" : ""));
            if ("completed".equals(s.status)) return s;
            Thread.sleep(8000);
        } while (System.currentTimeMillis() < deadline);
        return s;
    }

    // ------------------------------------------------------------ artifacts

    public static final class Artifact {
        public long id;
        public String name;
        public long sizeBytes;
    }

    public List<Artifact> listArtifacts(long runId) throws Exception {
        HttpURLConnection c = open("https://api.github.com/repos/" + owner + "/" + repo
                + "/actions/runs/" + runId + "/artifacts", "GET");
        failIfError(c, "List artifacts");
        Map<String, Object> r = obj(Json.parse(body(c)));
        c.disconnect();
        List<Artifact> out = new java.util.ArrayList<>();
        for (Object o : list(r.get("artifacts"))) {
            Map<String, Object> m = obj(o);
            Artifact a = new Artifact();
            a.id = ((Number) m.get("id")).longValue();
            a.name = (String) m.get("name");
            a.sizeBytes = ((Number) m.get("size_in_bytes")).longValue();
            out.add(a);
        }
        return out;
    }

    /** Downloads an artifact (zip) to outFile, following the redirect to blob storage without leaking the token there. */
    public void downloadArtifactZip(long artifactId, File outFile) throws Exception {
        String url = "https://api.github.com/repos/" + owner + "/" + repo
                + "/actions/artifacts/" + artifactId + "/zip/zip";
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            boolean isGithub = url.contains("api.github.com");
            if (isGithub) c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code == 302 || code == 301 || code == 303 || code == 307 || code == 308) {
                url = c.getHeaderField("Location");
                c.disconnect();
                continue;
            }
            if (code >= 400) throw new IOException("Download artifact failed (HTTP " + code + ")");
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(outFile)) {
                Util.copy(in, o);
            }
            c.disconnect();
            return;
        }
        throw new IOException("Too many redirects downloading artifact");
    }
}
