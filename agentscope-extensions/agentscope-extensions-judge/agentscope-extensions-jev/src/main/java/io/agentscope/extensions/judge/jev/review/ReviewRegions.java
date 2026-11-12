/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.extensions.judge.jev.review;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Evidence locations derived from supplied text, never from model-generated line numbers. */
public final class ReviewRegions {
    public enum Side {
        OLD,
        NEW
    }

    public record Region(String id, int startLine, int endLine, Side side, String text) {}

    private static final Pattern HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private ReviewRegions() {}

    public static List<Region> source(String content, int linesPerRegion) {
        if (linesPerRegion < 1)
            throw new IllegalArgumentException("positive region length required");
        if (content.isBlank()) return List.of();
        String[] lines = content.split("\\R", -1);
        int length = lines.length;
        if (lines[length - 1].isEmpty()) length--; // newline is not an extra source line
        List<Region> result = new ArrayList<>();
        for (int start = 0; start < length; ) {
            int end = (int) Math.min((long) start + linesPerRegion, length);
            result.add(
                    new Region(
                            "R" + (result.size() + 1),
                            start + 1,
                            end,
                            Side.NEW,
                            String.join("\n", java.util.Arrays.copyOfRange(lines, start, end))));
            start = end;
        }
        return List.copyOf(result);
    }

    /** Strict one-file unified diff; malformed, binary and combined diffs have no valid location. */
    public static List<Region> patch(String patch) {
        if (patch.isBlank()) return List.of();
        String[] lines = patch.split("\\R", -1);
        List<Region> regions = new ArrayList<>();
        List<String> hunk = null;
        int oldStart = 0, newStart = 0, oldCount = 0, newCount = 0, oldSeen = 0, newSeen = 0;
        boolean fileHeader = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i == lines.length - 1 && line.isEmpty()) continue;
            var matcher = HEADER.matcher(line);
            if (matcher.matches()) {
                if (hunk != null)
                    flush(regions, hunk, oldStart, oldCount, newStart, newCount, oldSeen, newSeen);
                oldStart = Integer.parseInt(matcher.group(1));
                oldCount = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
                newStart = Integer.parseInt(matcher.group(3));
                newCount = matcher.group(4) == null ? 1 : Integer.parseInt(matcher.group(4));
                if ((oldCount > 0 && oldStart == 0)
                        || (newCount > 0 && newStart == 0)
                        || (oldCount == 0 && newCount == 0))
                    throw new IllegalArgumentException("invalid diff range");
                oldSeen = 0;
                newSeen = 0;
                hunk = new ArrayList<>();
                hunk.add(line);
            } else if (hunk != null) {
                if (line.startsWith("+")) newSeen++;
                else if (line.startsWith("-")) oldSeen++;
                else if (line.startsWith(" ")) {
                    oldSeen++;
                    newSeen++;
                } else if (!line.equals("\\ No newline at end of file"))
                    throw new IllegalArgumentException("unsupported diff content");
                hunk.add(line);
            } else if (line.startsWith("diff --git ")) {
                if (fileHeader) throw new IllegalArgumentException("one file per diff required");
                fileHeader = true;
            } else if (!(line.startsWith("--- ")
                    || line.startsWith("+++ ")
                    || line.startsWith("index ")
                    || line.startsWith("new file mode ")
                    || line.startsWith("deleted file mode ")
                    || line.startsWith("old mode ")
                    || line.startsWith("new mode ")
                    || line.startsWith("similarity index ")
                    || line.startsWith("rename from ")
                    || line.startsWith("rename to ")))
                throw new IllegalArgumentException("unsupported diff preamble");
        }
        if (hunk != null)
            flush(regions, hunk, oldStart, oldCount, newStart, newCount, oldSeen, newSeen);
        return List.copyOf(regions);
    }

    private static void flush(
            List<Region> output,
            List<String> hunk,
            int oldStart,
            int oldCount,
            int newStart,
            int newCount,
            int oldSeen,
            int newSeen) {
        if (oldSeen != oldCount || newSeen != newCount)
            throw new IllegalArgumentException("diff hunk counts do not match content");
        int start = newCount > 0 ? newStart : oldStart;
        int length = newCount > 0 ? newCount : oldCount;
        int end;
        try {
            end = Math.addExact(start, length - 1);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("diff line overflow");
        }
        output.add(
                new Region(
                        "hunk_" + (output.size() + 1),
                        start,
                        end,
                        newCount > 0 ? Side.NEW : Side.OLD,
                        String.join("\n", hunk)));
    }
}
