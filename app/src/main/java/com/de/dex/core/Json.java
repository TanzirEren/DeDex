package com.de.dex.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON parser (objects, arrays, strings, numbers, booleans, null). */
public final class Json {
    private final String s;
    private int p;

    private Json(String s) { this.s = s; }

    public static Object parse(String s) { return new Json(s).val(); }

    private void ws() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }

    private Object val() {
        ws();
        char c = s.charAt(p);
        if (c == '{') {
            p++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (s.charAt(p) == '}') { p++; return m; }
            while (true) {
                ws();
                String k = str();
                ws();
                p++; // ':'
                m.put(k, val());
                ws();
                if (s.charAt(p++) == '}') break;
            }
            return m;
        }
        if (c == '[') {
            p++;
            List<Object> l = new ArrayList<>();
            ws();
            if (s.charAt(p) == ']') { p++; return l; }
            while (true) {
                l.add(val());
                ws();
                if (s.charAt(p++) == ']') break;
            }
            return l;
        }
        if (c == '"') return str();
        if (s.startsWith("true", p)) { p += 4; return Boolean.TRUE; }
        if (s.startsWith("false", p)) { p += 5; return Boolean.FALSE; }
        if (s.startsWith("null", p)) { p += 4; return null; }
        int st = p;
        while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
        String n = s.substring(st, p);
        if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.valueOf(n);
        return Long.valueOf(n);
    }

    private String str() {
        p++;
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(p++);
            if (c == '"') break;
            if (c == '\\') {
                char e = s.charAt(p++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(p, p + 4), 16)); p += 4; break;
                    default: b.append(e);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
