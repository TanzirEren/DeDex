package com.de.dex.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Builds an APK where the loader is replaced by the recovered DEX files (unsigned, 4-byte aligned). */
public final class ApkRebuilder {
    private static final Pattern ROOT_DEX = Pattern.compile("classes\\d*\\.dex");
    private static final Pattern SIGN = Pattern.compile("META-INF/(MANIFEST\\.MF|.*\\.(SF|RSA|DSA|EC))");

    private ApkRebuilder() {}

    private static final class Counting extends FilterOutputStream {
        long count;
        Counting(OutputStream o) { super(o); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }

    /** @return human readable report of what was changed */
    public static String rebuild(File apk, File dexDir, Mtenc m, File outApk) throws Exception {
        StringBuilder rep = new StringBuilder();
        try (ZipFile zf = new ZipFile(apk);
             Counting cnt = new Counting(new FileOutputStream(outApk));
             ZipOutputStream zo = new ZipOutputStream(cnt)) {
            String prefix = "assets/" + m.folder + "/";
            Map<String, String> repl = new HashMap<>();
            repl.put(m.folder + ".Legacy", m.app);
            repl.put(m.folder + ".Factory", m.factory.isEmpty() ? "android.app.AppComponentFactory" : m.factory);

            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String n = ze.getName();
                if (ze.isDirectory() || SIGN.matcher(n).matches() || n.startsWith(prefix)) continue;
                if (ROOT_DEX.matcher(n).matches()) continue;
                if (n.equals("AndroidManifest.xml")) {
                    int[] c = new int[1];
                    byte[] patched = Axml.replaceStrings(Util.readAll(zf, ze), repl, c);
                    rep.append("Manifest: ").append(c[0]).append(" loader name(s) restored\n");
                    putDeflated(zo, n, patched);
                } else if (ze.getMethod() == ZipEntry.STORED) {
                    putStored(zo, cnt, ze, zf);
                } else {
                    ZipEntry ne = new ZipEntry(n);
                    ne.setMethod(ZipEntry.DEFLATED);
                    zo.putNextEntry(ne);
                    try (InputStream in = zf.getInputStream(ze)) { Util.copy(in, zo); }
                    zo.closeEntry();
                }
            }
            int dexCount = 0;
            for (Mtenc.Entry e : m.entries) {
                File f = new File(dexDir, e.name);
                if (!f.isFile()) throw new IOException("Missing extracted " + e.name + ", run Out first");
                ZipEntry ne = new ZipEntry(e.name);
                ne.setMethod(ZipEntry.DEFLATED);
                zo.putNextEntry(ne);
                try (InputStream in = new FileInputStream(f)) { Util.copy(in, zo); }
                zo.closeEntry();
                dexCount++;
            }
            rep.append("DEX written: ").append(dexCount).append('\n');
            rep.append("Removed: loader, ").append(prefix).append("*, old signature\n");
        }
        return rep.toString();
    }

    private static void putDeflated(ZipOutputStream zo, String n, byte[] data) throws IOException {
        ZipEntry ne = new ZipEntry(n);
        ne.setMethod(ZipEntry.DEFLATED);
        zo.putNextEntry(ne);
        zo.write(data);
        zo.closeEntry();
    }

    private static void putStored(ZipOutputStream zo, Counting cnt, ZipEntry src, ZipFile zf) throws IOException {
        ZipEntry ne = new ZipEntry(src.getName());
        ne.setMethod(ZipEntry.STORED);
        ne.setSize(src.getSize());
        ne.setCompressedSize(src.getSize());
        ne.setCrc(src.getCrc());
        int headerLen = 30 + src.getName().getBytes(StandardCharsets.UTF_8).length;
        int r = (int) ((cnt.count + headerLen) % 4);
        int pad = (4 - r) % 4;
        if (pad != 0) {
            int len = pad + 4;
            ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
            b.putShort((short) 0xD935).putShort((short) (len - 4));
            ne.setExtra(b.array());
        }
        zo.putNextEntry(ne);
        try (InputStream in = zf.getInputStream(src)) { Util.copy(in, zo); }
        zo.closeEntry();
    }
}
