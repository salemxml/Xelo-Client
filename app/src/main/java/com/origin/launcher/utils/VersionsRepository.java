package com.origin.launcher.utils;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VersionsRepository {
    private static final String TAG = "VersionsRepository";

    /** Primary CDN — always newest-first. */
    private static final String REMOTE_URL =
            "https://raw.githubusercontent.com/Xelo-Client/cdn/refs/heads/main/results.txt";

    /** Fallback CDN used when the primary is unreachable. */
    private static final String FALLBACK_URL =
            "https://cdn.jsdelivr.net/gh/Xelo-Client/cdn@main/results.txt";

    private static final String CACHE_FILE_NAME = "mcpe_versions.txt";

    public static class VersionEntry {
        public final String title;
        public final String url;
        public final boolean isBeta;

        public VersionEntry(String title, String url, boolean isBeta) {
            this.title = title;
            this.url = url;
            this.isBeta = isBeta;
        }
    }

    public List<VersionEntry> getVersions(Context context) {
        File cacheFile = new File(context.getCacheDir(), CACHE_FILE_NAME);

        // 1. Try primary CDN
        try {
            List<String> lines = downloadLines(REMOTE_URL);
            if (!lines.isEmpty()) {
                writeCache(cacheFile, lines);
                return sortedEntries(parse(lines));
            }
        } catch (Exception e) {
            Log.w(TAG, "Primary CDN failed, trying fallback: " + e.getMessage());
        }

        // 2. Try fallback CDN
        try {
            List<String> lines = downloadLines(FALLBACK_URL);
            if (!lines.isEmpty()) {
                writeCache(cacheFile, lines);
                return sortedEntries(parse(lines));
            }
        } catch (Exception e) {
            Log.w(TAG, "Fallback CDN failed, using cache: " + e.getMessage());
        }

        // 3. Use local cache
        try {
            if (cacheFile.exists()) {
                List<String> cached = readCache(cacheFile);
                return sortedEntries(parse(cached));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read cached versions", e);
        }

        Log.w(TAG, "No versions available");
        return new ArrayList<>();
    }

    public void clearCache(Context context) {
        File cacheFile = new File(context.getCacheDir(), CACHE_FILE_NAME);
        if (cacheFile.exists()) {
            cacheFile.delete();
            Log.d(TAG, "Cleared version cache");
        }
    }

    // ─── Sorting ──────────────────────────────────────────────────────────────

    /**
     * Sorts version entries from newest to oldest so that the latest Minecraft
     * Bedrock version (e.g. 1.26.23.1) always appears first in the list.
     */
    private List<VersionEntry> sortedEntries(List<VersionEntry> entries) {
        List<VersionEntry> sorted = new ArrayList<>(entries);
        Collections.sort(sorted, (a, b) -> compareVersionStrings(extractVersion(b.title), extractVersion(a.title)));
        return sorted;
    }

    /** Numeric version comparison — works for any depth (1.21.130, 1.26.23.1, …). */
    private int compareVersionStrings(String v1, String v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;
        String[] p1 = v1.split("\\.");
        String[] p2 = v2.split("\\.");
        int maxLen = Math.max(p1.length, p2.length);
        for (int i = 0; i < maxLen; i++) {
            int n1 = i < p1.length ? safeParseInt(p1[i]) : 0;
            int n2 = i < p2.length ? safeParseInt(p2[i]) : 0;
            if (n1 != n2) return Integer.compare(n1, n2);
        }
        return 0;
    }

    private int safeParseInt(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (NumberFormatException e) { return 0; }
    }

    // ─── Network ──────────────────────────────────────────────────────────────

    private List<String> downloadLines(String rawUrl) throws Exception {
        HttpURLConnection connection = null;
        List<String> result = new ArrayList<>();
        try {
            URL url = new URL(rawUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36");
            connection.connect();

            int code = connection.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);

            try (InputStream in = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) result.add(line);
                }
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
        return result;
    }

    // ─── Cache ────────────────────────────────────────────────────────────────

    private void writeCache(File file, List<String> lines) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (String l : lines) { writer.write(l); writer.newLine(); }
        }
    }

    private List<String> readCache(File file) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) lines.add(line);
            }
        }
        return lines;
    }

    // ─── Parsing ──────────────────────────────────────────────────────────────

    private List<VersionEntry> parse(List<String> lines) {
        List<VersionEntry> list = new ArrayList<>();
        for (String raw : lines) {
            Parsed p = parseLine(raw);
            if (p != null) list.add(new VersionEntry(p.title, p.url, p.isBeta));
            else Log.w(TAG, "Failed to parse: " + raw);
        }
        return list;
    }

    private static class Parsed { String title; String url; boolean isBeta; }

    private Parsed parseLine(String raw) {
        String title = null, url = null;

        if (raw.contains("|")) {
            String[] parts = raw.split("\\|", 2);
            if (parts.length == 2) { title = parts[0].trim(); url = parts[1].trim(); }
        }
        if (title == null || url == null) {
            String[] parts = raw.split("\t", 2);
            if (parts.length == 2 && parts[1].startsWith("http")) { title = parts[0].trim(); url = parts[1].trim(); }
        }
        if (title == null || url == null) {
            int idx = raw.lastIndexOf(" http");
            if (idx == -1) idx = raw.lastIndexOf("\thttp");
            if (idx != -1) { title = raw.substring(0, idx).trim(); url = raw.substring(idx + 1).trim(); }
            else {
                int h = raw.indexOf("http");
                if (h > 0) { title = raw.substring(0, h).trim(); url = raw.substring(h).trim(); }
            }
        }

        if (title == null || url == null || !url.startsWith("http")) return null;

        String version = extractVersion(title);
        int dotCount = version != null ? count(version, '.') : count(title, '.');
        // 2 dots  → stable  (e.g. 1.26.23)
        // 3+ dots → beta    (e.g. 1.26.23.1)
        boolean isBeta = dotCount >= 3;

        Parsed p = new Parsed();
        p.title = title.replace(":", "").trim();
        p.url = url;
        p.isBeta = isBeta;
        return p;
    }

    private String extractVersion(String title) {
        if (title == null) return null;
        try {
            Matcher m = Pattern.compile("(\\d+\\.\\d+\\.\\d+(?:\\.\\d+)*)").matcher(title);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return null;
    }

    private int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
