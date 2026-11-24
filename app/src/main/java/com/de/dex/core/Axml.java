package com.de.dex.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Replaces strings inside the string pool of a binary AndroidManifest.xml. */
public final class Axml {
    private Axml() {}

    /** Returns patched bytes; count[0] receives the number of replaced strings. */
    public static byte[] replaceStrings(byte[] in, Map<String, String> repl, int[] count) {
        ByteBuffer bb = ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.getShort(0) != 0x0003) throw new IllegalArgumentException("Not a binary XML");
        int poolOff = 8;
        if (bb.getShort(poolOff) != 0x0001) throw new IllegalArgumentException("No string pool");
        int poolSize = bb.getInt(poolOff + 4);
        int strCount = bb.getInt(poolOff + 8);
        int styleCount = bb.getInt(poolOff + 12);
        int flags = bb.getInt(poolOff + 16);
        int strStart = bb.getInt(poolOff + 20);
        int styleStart = bb.getInt(poolOff + 24);
        boolean utf8 = (flags & 0x100) != 0;
        int offsTable = poolOff + 28;

        List<String> strs = new ArrayList<>();
        for (int i = 0; i < strCount; i++) {
            int p = poolOff + strStart + bb.getInt(offsTable + 4 * i);
            strs.add(utf8 ? readUtf8(in, p) : readUtf16(bb, p));
        }
        byte[] styleBlob = new byte[0];
        if (styleCount > 0 && styleStart != 0) {
            int s = poolOff + styleStart;
            int e = poolOff + poolSize;
            styleBlob = new byte[e - s];
            System.arraycopy(in, s, styleBlob, 0, styleBlob.length);
        }
        int n = 0;
        for (int i = 0; i < strs.size(); i++) {
            String r = repl.get(strs.get(i));
            if (r != null) { strs.set(i, r); n++; }
        }
        count[0] = n;

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offs = new int[strCount];
        for (int i = 0; i < strCount; i++) {
            offs[i] = data.size();
            byte[] enc = utf8 ? encUtf8(strs.get(i)) : encUtf16(strs.get(i));
            data.write(enc, 0, enc.length);
        }
        while (data.size() % 4 != 0) data.write(0);

        int headerLen = 28 + 4 * strCount + 4 * styleCount;
        int newStrStart = headerLen;
        int newStyleStart = styleCount > 0 ? headerLen + data.size() : 0;
        int newPoolSize = headerLen + data.size() + styleBlob.length;

        ByteBuffer out = ByteBuffer.allocate(poolOff + newPoolSize + (in.length - poolOff - poolSize))
                .order(ByteOrder.LITTLE_ENDIAN);
        out.put(in, 0, poolOff);
        out.putShort((short) 0x0001);
        out.putShort((short) 28);
        out.putInt(newPoolSize);
        out.putInt(strCount);
        out.putInt(styleCount);
        out.putInt(flags);
        out.putInt(newStrStart);
        out.putInt(newStyleStart);
        for (int o : offs) out.putInt(o);
        for (int i = 0; i < styleCount; i++) out.putInt(bb.getInt(offsTable + 4 * strCount + 4 * i));
        out.put(data.toByteArray());
        out.put(styleBlob);
        out.put(in, poolOff + poolSize, in.length - poolOff - poolSize);
        out.putInt(4, out.position());
        return out.array();
    }

    private static String readUtf8(byte[] b, int p) {
        int c = b[p] & 0xff;
        p += (c & 0x80) != 0 ? 2 : 1;
        int len = b[p] & 0xff;
        p++;
        if ((len & 0x80) != 0) { len = ((len & 0x7f) << 8) | (b[p] & 0xff); p++; }
        return new String(b, p, len, StandardCharsets.UTF_8);
    }

    private static String readUtf16(ByteBuffer bb, int p) {
        int len = bb.getShort(p) & 0xffff;
        p += 2;
        if ((len & 0x8000) != 0) { len = ((len & 0x7fff) << 16) | (bb.getShort(p) & 0xffff); p += 2; }
        char[] c = new char[len];
        for (int i = 0; i < len; i++) c[i] = bb.getChar(p + 2 * i);
        return new String(c);
    }

    private static byte[] encUtf8(String s) {
        byte[] u = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeLen8(o, s.length());
        writeLen8(o, u.length);
        o.write(u, 0, u.length);
        o.write(0);
        return o.toByteArray();
    }

    private static void writeLen8(ByteArrayOutputStream o, int len) {
        if (len > 0x7f) { o.write(((len >> 8) & 0x7f) | 0x80); o.write(len & 0xff); }
        else o.write(len);
    }

    private static byte[] encUtf16(String s) {
        ByteBuffer b = ByteBuffer.allocate(4 + 2 * s.length() + 2 + 2).order(ByteOrder.LITTLE_ENDIAN);
        if (s.length() > 0x7fff) {
            b.putShort((short) (((s.length() >> 16) & 0x7fff) | 0x8000));
            b.putShort((short) (s.length() & 0xffff));
        } else {
            b.putShort((short) s.length());
        }
        for (int i = 0; i < s.length(); i++) b.putChar(s.charAt(i));
        b.putShort((short) 0);
        byte[] r = new byte[b.position()];
        System.arraycopy(b.array(), 0, r, 0, r.length);
        return r;
    }
}
