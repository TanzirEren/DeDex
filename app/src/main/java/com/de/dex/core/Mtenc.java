package com.de.dex.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Reader for the "MTENC" DEX protector (catalog + AES-GCM protected DEX blocks). */
public final class Mtenc {
    public static final String DEFAULT_SEED =
            "MTENC_MASK_24355a4adc05f7f411a1fc4ab5a34cf17a1ca56fd332f98b6e6887ccdab43f71";
    private static final Pattern CATALOG = Pattern.compile("assets/([^/]+)/catalog");
    private static final Pattern SEED = Pattern.compile("MTENC_MASK_[0-9a-f]{64}");

    public static final class Entry {
        public String name, path;
        public long size, crc;
    }

    public final String folder, id, app, factory;
    public final boolean res;
    public final List<Entry> entries = new ArrayList<>();
    private final byte[] rawKey;
    private final List<String> seeds = new ArrayList<>();
    private byte[] goodKey;

    @SuppressWarnings("unchecked")
    private Mtenc(String folder, Map<String, Object> m) {
        this.folder = folder;
        this.id = (String) m.get("id");
        this.app = m.containsKey("app") ? (String) m.get("app") : "android.app.Application";
        Object f = m.get("factory");
        this.factory = f == null ? "" : (String) f;
        this.res = Boolean.TRUE.equals(m.get("res"));
        this.rawKey = Util.unhex((String) m.get("key"));
        Map<String, Object> objs = (Map<String, Object>) m.get("objects");
        for (Map.Entry<String, Object> e : objs.entrySet()) {
            Map<String, Object> o = (Map<String, Object>) e.getValue();
            Entry en = new Entry();
            en.name = e.getKey();
            en.path = (String) o.get("path");
            en.size = ((Number) o.get("size")).longValue();
            en.crc = ((Number) o.get("crc")).longValue();
            entries.add(en);
        }
        seeds.add(DEFAULT_SEED);
    }

    /** Returns null when the APK has no MTENC catalog. */
    @SuppressWarnings("unchecked")
    public static Mtenc open(ZipFile zf) {
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                Matcher mt = CATALOG.matcher(ze.getName());
                if (!mt.matches() || ze.getSize() > (1 << 24)) continue;
                String txt = new String(Util.readAll(zf, ze), StandardCharsets.UTF_8).trim();
                if (!txt.startsWith("{")) continue;
                Object o = Json.parse(txt);
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;
                if (!m.containsKey("objects") || !m.containsKey("key") || !m.containsKey("id")) continue;
                Mtenc x = new Mtenc(mt.group(1), m);
                x.findSeeds(zf);
                return x;
            }
        } catch (Exception ignored) {
            // not an MTENC apk
        }
        return null;
    }

    /** Looks for other mask seeds inside the small loader dex. */
    private void findSeeds(ZipFile zf) {
        try {
            ZipEntry ze = zf.getEntry("classes.dex");
            if (ze == null || ze.getSize() > 4 * 1024 * 1024) return;
            String s = new String(Util.readAll(zf, ze), StandardCharsets.ISO_8859_1);
            Matcher m = SEED.matcher(s);
            Set<String> set = new LinkedHashSet<>();
            while (m.find()) set.add(m.group());
            set.remove(DEFAULT_SEED);
            seeds.addAll(set);
        } catch (Exception ignored) {
            // keep default seed
        }
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] r = new byte[a.length];
        for (int i = 0; i < a.length; i++) r[i] = (byte) (a[i] ^ b[i % b.length]);
        return r;
    }

    private byte[] gcm(byte[] key, byte[] blob, String aad) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, blob, 0, 12));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return c.doFinal(blob, 12, blob.length - 12);
    }

    /** Decrypts one protected block and returns the original DEX bytes (size and CRC verified). */
    public byte[] decode(ZipFile zf, Entry e) throws Exception {
        ZipEntry ze = zf.getEntry(e.path);
        if (ze == null) throw new IOException("Missing " + e.path);
        byte[] blob = Util.readAll(zf, ze);
        if (blob.length < 28) throw new IOException("Block too small: " + e.path);
        byte[] plain = null;
        List<byte[]> keys = new ArrayList<>();
        if (goodKey != null) keys.add(goodKey);
        for (String sd : seeds) keys.add(xor(rawKey, Util.unhex(sd.substring(11))));
        keys.add(rawKey);
        outer:
        for (byte[] k : keys) {
            for (String an : new String[]{e.name, e.path}) {
                try {
                    plain = gcm(k, blob, id + "/" + an);
                    goodKey = k;
                    break outer;
                } catch (Exception ignored) {
                    // try next key / aad
                }
            }
        }
        if (plain == null) throw new IOException("Decrypt failed for " + e.name + " (unknown key mask?)");
        plain = resolve(zf, plain, e);
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(plain);
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.min(e.size, 1 << 28));
            byte[] buf = new byte[65536];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                bo.write(buf, 0, n);
            }
            byte[] dex = bo.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(dex);
            if (dex.length != e.size || crc.getValue() != e.crc) {
                throw new IOException("Size/CRC mismatch for " + e.name);
            }
            return dex;
        } finally {
            inf.end();
        }
    }

    private static byte[] resolve(ZipFile zf, byte[] d, Entry e) throws Exception {
        if (d.length == 0 || d[0] != 7 || d.length > 0x800) return d;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(d));
        if (in.readInt() != 0x7444d31) throw new IOException("Bad dictionary tag");
        if (!"MTENC_NESTED_SHA256_V1".equals(in.readUTF())) throw new IOException("Bad dictionary codec");
        String path = in.readUTF();
        long off = in.readLong(), len = in.readLong();
        in.readLong();
        in.readLong();
        byte[] digest = new byte[32];
        in.readFully(digest);
        ZipEntry ze = zf.getEntry(path);
        if (ze == null) throw new IOException("Dictionary asset missing: " + path);
        byte[] all = Util.readAll(zf, ze);
        byte[] blob = new byte[(int) len];
        System.arraycopy(all, (int) off, blob, 0, (int) len);
        if (!MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(blob))) {
            throw new IOException("Dictionary SHA-256 mismatch");
        }
        return blob;
    }

    public List<String> seedList() { return Collections.unmodifiableList(seeds); }
}
