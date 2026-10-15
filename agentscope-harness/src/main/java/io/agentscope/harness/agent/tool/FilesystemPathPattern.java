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
package io.agentscope.harness.agent.tool;

import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import java.util.regex.Pattern;

/** Portable lexical matching for filesystem tool permission rules. */
final class FilesystemPathPattern {

    private FilesystemPathPattern() {}

    static boolean matches(String glob, String path) {
        String portable = glob.replace('\\', '/');
        for (String segment : portable.split("/")) {
            if (segment.equals("..") || (segment.contains("**") && !segment.equals("**"))) {
                throw new IllegalArgumentException(
                        "Path rules cannot contain '..'; '**' must occupy a whole segment: "
                                + glob);
            }
        }
        String pattern = stripRoot(normalize(portable));
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '/' && pattern.substring(i).equals("/**")) {
                regex.append("(?:/.*)?");
                break;
            }
            if (c == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                i++;
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '/') {
                    regex.append("(?:[^/]+/)*");
                    i++;
                } else {
                    regex.append(".*");
                }
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.append('$').toString(), Pattern.DOTALL)
                .matcher(stripRoot(normalize(path)))
                .matches();
    }

    static String normalize(String path) {
        return WorkspacePathNormalizer.normalizeLexically(path);
    }

    private static String stripRoot(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
