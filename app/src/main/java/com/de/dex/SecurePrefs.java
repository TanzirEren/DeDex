package com.de.dex;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/** Encrypted on-device storage for the GitHub PAT and repo settings. */
public final class SecurePrefs {
    private static final String FILE = "dedex_secure";
    private static final String K_TOKEN = "gh_token";
    private static final String K_OWNER = "gh_owner";
    private static final String K_REPO = "gh_repo";
    private static final String K_WORKFLOW = "gh_workflow";
    private static final String K_BRANCH = "gh_branch";

    private final SharedPreferences prefs;

    public SecurePrefs(Context ctx) {
        try {
            MasterKey key = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            prefs = EncryptedSharedPreferences.create(
                    ctx, FILE, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            throw new RuntimeException("Could not open secure storage", e);
        }
    }

    public String token() { return prefs.getString(K_TOKEN, ""); }
    public String owner() { return prefs.getString(K_OWNER, ""); }
    public String repo() { return prefs.getString(K_REPO, ""); }
    public String workflow() { return prefs.getString(K_WORKFLOW, "memory-dump.yml"); }
    public String branch() { return prefs.getString(K_BRANCH, "main"); }

    public void save(String token, String owner, String repo, String workflow, String branch) {
        prefs.edit()
                .putString(K_TOKEN, token)
                .putString(K_OWNER, owner)
                .putString(K_REPO, repo)
                .putString(K_WORKFLOW, workflow)
                .putString(K_BRANCH, branch)
                .apply();
    }

    public boolean isConfigured() {
        return !token().isEmpty() && !owner().isEmpty() && !repo().isEmpty();
    }
}
