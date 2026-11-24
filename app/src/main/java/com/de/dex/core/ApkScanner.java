package com.de.dex.core;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Looks inside an APK: protector catalog, plain DEX files and DEX hidden under other names. */
public final class ApkScanner {
    private ApkScanner() {}

    public static final class Info {
        public Mtenc mtenc;
        public final List<String> dexEntries = new ArrayList<>();
        public int entryCount;
        public String text;
    }

    public static Info scan(File apk) throws Exception {
        Info info = new Info();
        try (ZipFile zf = new ZipFile(apk)) {
            info.mtenc = Mtenc.open(zf);
            byte[] head = new byte[8];
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                info.entryCount++;
                if (ze.isDirectory() || ze.getSize() < 0x70) continue;
                try (InputStream in = zf.getInputStream(ze)) {
                    int n = 0, r;
                    while (n < 8 && (r = in.read(head, n, 8 - n)) > 0) n += r;
                    if (DexTools.isDex(head, n)) info.dexEntries.add(ze.getName());
                }
            }
            StringBuilder b = new StringBuilder();
            b.append("SCAN RESULT\n");
            b.append("Entries: ").append(info.entryCount).append("\n");
            b.append("Plain DEX (by magic): ").append(info.dexEntries.size()).append("\n");
            for (String s : info.dexEntries) {
                ZipEntry z = zf.getEntry(s);
                b.append("  ").append(s).append("  ").append(Util.human(z.getSize())).append('\n');
            }
            if (info.mtenc != null) {
                Mtenc m = info.mtenc;
                b.append("\nPROTECTOR: MTENC (loader package ").append(m.folder).append(")\n");
                b.append("Original app class: ").append(m.app).append('\n');
                b.append("Original factory: ").append(m.factory.isEmpty() ? "(none)" : m.factory).append('\n');
                b.append("Encrypted DEX blocks: ").append(m.entries.size()).append('\n');
                for (Mtenc.Entry e : m.entries) {
                    b.append("  ").append(e.path).append(" -> ").append(e.name)
                            .append("  ").append(Util.human(e.size)).append('\n');
                }
                b.append("Mask seeds known: ").append(m.seedList().size()).append('\n');
            } else {
                b.append("\nNo MTENC protector found.\n");
            }
            info.text = b.toString();
        }
        return info;
    }
}
