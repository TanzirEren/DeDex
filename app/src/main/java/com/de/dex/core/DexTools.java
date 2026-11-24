package com.de.dex.core;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public final class DexTools {
    private DexTools() {}

    public static boolean isDex(byte[] b, int n) {
        return n >= 8 && b[0] == 'd' && b[1] == 'e' && b[2] == 'x' && b[3] == '\n' && b[7] == 0;
    }

    private static String strAt(byte[] d, int idx) {
        int p = Util.le32(d, Util.le32(d, 0x3C) + 4 * idx);
        while ((d[p] & 0x80) != 0) p++;
        p++;
        int e = p;
        while (e < d.length && d[e] != 0) e++;
        return new String(d, p, e - p, StandardCharsets.UTF_8);
    }

    public static void strings(byte[] d, Consumer<String> out) {
        if (!isDex(d, d.length) || d.length < 0x70) return;
        int n = Util.le32(d, 0x38);
        for (int i = 0; i < n; i++) out.accept(strAt(d, i));
    }

    /** Class descriptors defined by the DEX, e.g. Lorg/telegram/ui/Foo; */
    public static void classes(byte[] d, Consumer<String> out) {
        if (!isDex(d, d.length) || d.length < 0x70) return;
        int toff = Util.le32(d, 0x44);
        int cn = Util.le32(d, 0x60), coff = Util.le32(d, 0x64);
        for (int i = 0; i < cn; i++) {
            int classIdx = Util.le32(d, coff + 32 * i);
            out.accept(strAt(d, Util.le32(d, toff + 4 * classIdx)));
        }
    }
}
