/*
 * The MIT License
 *
 * Copyright (c) 2026 MojoHaus.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.codehaus.mojo.animal_sniffer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the Kotlin stratum of an SMAP, without using call-site (KotlinDebug) mappings as origins. */
final class KotlinSourceMap {
    private static final Pattern FILE = Pattern.compile("([0-9]+) (.+)");
    private static final Pattern LINE = Pattern.compile("([0-9]+)(?:#([0-9]+))?(?:,([0-9]+))?:([0-9]+)(?:,([0-9]+))?");

    private final Map<Integer, String[]> files = new HashMap<>();
    private final List<LineRange> ranges = new ArrayList<>();

    private KotlinSourceMap() {}

    static KotlinSourceMap parse(String debug) {
        if (debug == null) {
            return null;
        }
        String[] lines = debug.split("\\r?\\n", -1);
        if (lines.length < 4 || !"SMAP".equals(lines[0]) || !"Kotlin".equals(lines[2])) {
            return null;
        }
        int stratum = -1;
        for (int i = 3; i < lines.length; i++) {
            if ("*S Kotlin".equals(lines[i])) {
                if (stratum != -1) {
                    return null;
                }
                stratum = i;
            }
        }
        if (stratum == -1 || stratum + 1 >= lines.length || !"*F".equals(lines[stratum + 1])) {
            return null;
        }
        KotlinSourceMap result = new KotlinSourceMap();
        try {
            int i = stratum + 2;
            while (i < lines.length && !lines[i].startsWith("*")) {
                boolean extended = lines[i].startsWith("+ ");
                Matcher file = FILE.matcher(extended ? lines[i].substring(2) : lines[i]);
                if (!file.matches()) {
                    return null;
                }
                int id = positive(file.group(1));
                String name = file.group(2);
                String path = null;
                i++;
                if (extended) {
                    if (i == lines.length || lines[i].isEmpty() || lines[i].startsWith("*")) {
                        return null;
                    }
                    path = lines[i++];
                }
                if (result.files.put(id, new String[] {name, path}) != null) {
                    return null;
                }
            }
            if (i == lines.length || !"*L".equals(lines[i++])) {
                return null;
            }
            int fileId = -1;
            while (i < lines.length && !lines[i].startsWith("*")) {
                Matcher line = LINE.matcher(lines[i++]);
                if (!line.matches()) {
                    return null;
                }
                int input = positive(line.group(1));
                if (line.group(2) != null) {
                    fileId = positive(line.group(2));
                }
                int repeat = line.group(3) == null ? 1 : positive(line.group(3));
                int output = positive(line.group(4));
                int increment = line.group(5) == null ? 1 : positive(line.group(5));
                if (!result.files.containsKey(fileId)
                        || (long) input + repeat - 1 > Integer.MAX_VALUE
                        || (long) output + (long) repeat * increment > (long) Integer.MAX_VALUE + 1) {
                    return null;
                }
                result.ranges.add(new LineRange(fileId, input, repeat, output, increment));
            }
            if (i == lines.length
                    || !("*E".equals(lines[i]) || lines[i].startsWith("*S "))
                    || result.ranges.isEmpty()) {
                return null;
            }
            return result;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Returns an original source line only for a unique mapping to this class and source file. */
    int sourceLine(int outputLine, String owner, String sourceFile) {
        LineRange match = null;
        for (LineRange range : ranges) {
            if (outputLine >= range.output && (long) outputLine < range.outputEnd) {
                if (match != null) {
                    return -1;
                }
                match = range;
            }
        }
        if (match == null) {
            return -1;
        }
        String[] file = files.get(match.file);
        if (!owner.equals(file[1]) || !sourceFile.equals(file[0])) {
            return -1;
        }
        return match.input + (outputLine - match.output) / match.increment;
    }

    private static int positive(String value) {
        int result = Integer.parseInt(value);
        if (result <= 0) {
            throw new IllegalArgumentException("Non-positive SMAP value");
        }
        return result;
    }

    private static final class LineRange {
        final int file;
        final int input;
        final int output;
        final int increment;
        final long outputEnd;

        LineRange(int file, int input, int repeat, int output, int increment) {
            this.file = file;
            this.input = input;
            this.output = output;
            this.increment = increment;
            this.outputEnd = (long) output + (long) repeat * increment;
        }
    }
}
