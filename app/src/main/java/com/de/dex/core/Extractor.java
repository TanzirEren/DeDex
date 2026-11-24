package com.de.dex.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Exports every DEX it can reach: plain, hidden, and decrypted protector blocks. */
public final class Extractor {
    public interface Log { void line(String s); }

    private static final Pattern ROOT_DEX = Pattern.compile("classes\\d*\\.dex");

    private Extractor() {}

    public static List<File> extractAll(File apk, File outDir, ApkScanner.Info info, Log log) throws Exception {
        if (outDir.exists()) {
            File[] old = outDir.listFiles();
            if (old != null) for (File f : old) f.delete();
        }
        outDir.mkdirs();
        List<File> made = new ArrayList<>();
        try (ZipFile zf = new ZipFile(apk)) {
            Mtenc m = info.mtenc;
            for (String name : info.dexEntries) {
                String out;
                if (ROOT_DEX.matcher(name).matches()) out = (m != null ? "loader_" : "") + name;
                else out = "hidden_" + name.replace('/', '_');
                if (!out.endsWith(".dex")) out += ".dex";
                File f = new File(outDir, out);
                try (InputStream in = zf.getInputStream(zf.getEntry(name)); OutputStream o = new FileOutputStream(f)) {
                    Util.copy(in, o);
                }
                made.add(f);
                log.line("exported " + out + "  " + Util.human(f.length()));
            }
            if (m != null) {
                for (Mtenc.Entry e : m.entries) {
                    byte[] dex = m.decode(zf, e);
                    File f = new File(outDir, e.name);
                    try (OutputStream o = new FileOutputStream(f)) { o.write(dex); }
                    made.add(f);
                    log.line("decrypted " + e.name + "  " + Util.human(dex.length) + "  crc ok");
                }
            }
        }
        return made;
    }
}
