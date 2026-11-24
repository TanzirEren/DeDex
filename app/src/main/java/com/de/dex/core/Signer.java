package com.de.dex.core;

import com.android.apksig.ApkSigner;
import java.io.File;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** Signs an APK (v1+v2+v3) with the bundled debug key. */
public final class Signer {
    private Signer() {}

    public static void sign(InputStream keystore, File in, File out) throws Exception {
        char[] pw = "dedexdebug".toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(keystore, pw);
        PrivateKey key = (PrivateKey) ks.getKey("dedex", pw);
        X509Certificate cert = (X509Certificate) ks.getCertificate("dedex");
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder(
                "dedex", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(in)
                .setOutputApk(out)
                .setMinSdkVersion(26)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
    }
}
