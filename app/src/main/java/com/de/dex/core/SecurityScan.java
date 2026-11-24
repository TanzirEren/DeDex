package com.de.dex.core;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Static overview of one or more DEX files: URLs, hosts, risky API references, packages. */
public final class SecurityScan {
    private static final Pattern URL = Pattern.compile("https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%\\-]{4,160}");
    private static final String[] RISKY = {
            "Ljava/lang/Runtime;", "Ljava/lang/ProcessBuilder;", "Ldalvik/system/DexClassLoader;",
            "Ldalvik/system/InMemoryDexClassLoader;", "Landroid/telephony/SmsManager;",
            "Landroid/accounts/AccountManager;", "Landroid/provider/CallLog$Calls;",
            "Ljava/net/Socket;", "Ljava/net/HttpURLConnection;", "Landroid/app/admin/DevicePolicyManager;"
    };
    private static final Set<String> COMMON = new HashSet<>(Arrays.asList(
            "android", "androidx", "java", "javax", "kotlin", "kotlinx", "j$", "dalvik", "sun",
            "org.telegram", "org.jetbrains", "org.webrtc", "org.checkerframework", "org.json",
            "org.apache", "org.intellij", "org.unifiedpush", "org.slf4j", "org.xmlpull", "org.w3c",
            "org.xml", "com.google", "com.android", "com.stripe", "com.airbnb", "com.beloo",
            "com.github", "com.caverock", "com.googlecode", "com.coremedia", "com.mp4parser"));

    private SecurityScan() {}

    public static String run(List<File> dexFiles) throws Exception {
        Set<String> urls = new TreeSet<>();
        Set<String> hosts = new TreeSet<>();
        Set<String> risky = new TreeSet<>();
        Map<String, Integer> pk = new HashMap<>();
        int classCount = 0;
        for (File f : dexFiles) {
            byte[] d = Files.readAllBytes(f.toPath());
            final Set<String> riskySet = new HashSet<>(Arrays.asList(RISKY));
            DexTools.strings(d, s -> {
                if (s.length() > 9 && s.indexOf("://") > 0) {
                    Matcher m = URL.matcher(s);
                    while (m.find()) {
                        String u = m.group();
                        urls.add(u);
                        int st = u.indexOf("://") + 3, e = st;
                        while (e < u.length() && "/?#:".indexOf(u.charAt(e)) < 0) e++;
                        String h = u.substring(st, e).toLowerCase();
                        if (h.length() > 3 && h.matches("[a-z0-9][a-z0-9.-]*[a-z0-9]")) hosts.add(h);
                    }
                } else if (riskySet.contains(s)) {
                    risky.add(s);
                }
            });
            final int[] cc = {0};
            DexTools.classes(d, desc -> {
                cc[0]++;
                int last = desc.lastIndexOf('/');
                String pkg = last > 1 ? desc.substring(1, last).replace('/', '.') : "(default)";
                String[] seg = pkg.split("\\.");
                String key = seg.length >= 2 ? seg[0] + "." + seg[1] : seg[0];
                pk.merge(key, 1, Integer::sum);
            });
            classCount += cc[0];
        }
        StringBuilder b = new StringBuilder();
        b.append("SECURITY SCAN\n");
        b.append("DEX files: ").append(dexFiles.size()).append("   classes: ").append(classCount).append("\n\n");
        b.append("Hosts (").append(hosts.size()).append("):\n");
        int i = 0;
        for (String h : hosts) {
            if (i++ >= 80) { b.append("  ...\n"); break; }
            b.append("  ").append(h).append('\n');
        }
        b.append("\nRisky API references (presence only, not proof of abuse):\n");
        if (risky.isEmpty()) b.append("  none\n");
        for (String r : risky) b.append("  ").append(r).append('\n');
        b.append("\nPackages (* = not in the common list, check these):\n");
        List<Map.Entry<String, Integer>> list = new ArrayList<>(pk.entrySet());
        list.sort((x, y) -> y.getValue() - x.getValue());
        int shown = 0;
        for (Map.Entry<String, Integer> e : list) {
            boolean common = COMMON.contains(e.getKey()) || COMMON.contains(e.getKey().split("\\.")[0]);
            if (common && shown >= 12) continue;
            if (shown++ >= 40) break;
            b.append(common ? "    " : "  * ").append(e.getKey()).append("  (").append(e.getValue()).append(")\n");
        }
        b.append("\nURLs found: ").append(urls.size()).append(" (export report to read all)\n");
        return b.toString();
    }

    public static List<String> urlsOnly(List<File> dexFiles) throws Exception {
        List<String> out = new ArrayList<>();
        for (File f : dexFiles) {
            byte[] d = Files.readAllBytes(f.toPath());
            DexTools.strings(d, s -> {
                if (s.length() > 9 && s.indexOf("://") > 0) {
                    Matcher m = URL.matcher(s);
                    while (m.find()) out.add(m.group());
                }
            });
        }
        return out;
    }
}
