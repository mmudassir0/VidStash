package com.mudassir.vidstash;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts YouTube WebVTT captions to SubRip (.srt), which almost every video player
 * picks up automatically when it sits next to the video with the same name.
 *
 * Auto-generated captions repeat each line across "rolling" cues and carry per-word timing
 * tags; both are cleaned up so the result reads like normal subtitles.
 * Plain Java so it can be unit-tested off-device.
 */
public final class Subtitles {

    private Subtitles() {}

    private static final Pattern TIMING = Pattern.compile(
        "((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{3})\\s*-->\\s*((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{3})");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private static final class Cue {
        final long start, end;
        final List<String> lines;
        Cue(long start, long end, List<String> lines) { this.start = start; this.end = end; this.lines = lines; }
    }

    public static String vttToSrt(String vtt) {
        String[] rows = vtt.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<Cue> cues = new ArrayList<>();
        int i = 0;
        while (i < rows.length) {
            Matcher m = TIMING.matcher(rows[i]);
            if (!m.find()) { i++; continue; }
            long start = parseTime(m.group(1));
            long end = parseTime(m.group(2));
            i++;
            List<String> lines = new ArrayList<>();
            // Only a truly empty line ends a cue: auto-captions use a lone space as a line.
            while (i < rows.length && !rows[i].isEmpty()) {
                String text = unescape(TAG.matcher(rows[i]).replaceAll("")).trim();
                if (!text.isEmpty()) lines.add(text);
                i++;
            }
            cues.add(new Cue(start, end, lines));
        }

        // Drop lines already shown by the previous cue (rolling auto-captions), then empty
        // or near-instant cues.
        List<Cue> clean = new ArrayList<>();
        List<String> previous = new ArrayList<>();
        for (Cue c : cues) {
            List<String> fresh = new ArrayList<>();
            for (String line : c.lines) if (!previous.contains(line)) fresh.add(line);
            if (!c.lines.isEmpty()) previous = c.lines;
            if (fresh.isEmpty() || c.end - c.start < 50) continue;
            clean.add(new Cue(c.start, c.end, fresh));
        }

        StringBuilder out = new StringBuilder();
        int n = 1;
        for (Cue c : clean) {
            out.append(n++).append('\n')
               .append(formatTime(c.start)).append(" --> ").append(formatTime(c.end)).append('\n')
               .append(String.join("\n", c.lines)).append("\n\n");
        }
        return out.toString();
    }

    static long parseTime(String t) {
        String[] parts = t.replace(',', '.').split(":");
        long h = 0, m, ms;
        if (parts.length == 3) {
            h = Long.parseLong(parts[0]);
            m = Long.parseLong(parts[1]);
            ms = Math.round(Double.parseDouble(parts[2]) * 1000);
        } else {
            m = Long.parseLong(parts[0]);
            ms = Math.round(Double.parseDouble(parts[1]) * 1000);
        }
        return (h * 3600 + m * 60) * 1000 + ms;
    }

    static String formatTime(long ms) {
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d",
            ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000);
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'");
    }
}
