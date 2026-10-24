package com.zmlworks.poolmemo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal PoolSketch client: GET /{id}, POST /edit.php (multipart). */
final class PoolSketch {
    private static final String BASE = "https://plsk.net";
    private static final Pattern TA = Pattern.compile(
            "<textarea[^>]*>(.*?)</textarea>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern DISP = Pattern.compile(
            "id=\"disp\"[^>]*>(.*?)</div>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private PoolSketch() {}

    static String fetch(String id) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/" + id).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "PoolMemo/0.1 (Android)");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String html = readAll(in);
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);
        return extractContent(html);
    }

    static void save(String id, String txt) throws Exception {
        String boundary = "----PoolMemo" + System.currentTimeMillis();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writePart(body, boundary, "id", id);
        writePart(body, boundary, "txt", txt);
        writePart(body, boundary, "edit", "編集する");
        writePart(body, boundary, "dark", "1");
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] bytes = body.toByteArray();

        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/edit.php").openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("User-Agent", "PoolMemo/0.1 (Android)");
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(bytes);
        }
        int code = c.getResponseCode();
        // drain
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) readAll(in);
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);
    }

    private static void writePart(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    static String extractContent(String html) {
        if (html == null) return "";
        Matcher m = TA.matcher(html);
        if (m.find()) return unescape(m.group(1));
        m = DISP.matcher(html);
        if (m.find()) return unescape(m.group(1));
        return "";
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString(StandardCharsets.UTF_8.name());
    }
}
