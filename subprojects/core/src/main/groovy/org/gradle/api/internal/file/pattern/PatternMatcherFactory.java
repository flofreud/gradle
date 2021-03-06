/*
 * Copyright 2009 the original author or authors.
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
package org.gradle.api.internal.file.pattern;

import org.gradle.api.file.RelativePath;
import org.gradle.api.specs.Spec;

import java.util.ArrayList;
import java.util.List;

public class PatternMatcherFactory {

    public static final EndOfPathMatcher END_OF_PATH_MATCHER = new EndOfPathMatcher();

    public static Spec<RelativePath> getPatternMatcher(boolean partialMatchDirs, boolean caseSensitive, String pattern) {
        PathMatcher pathMatcher = compile(caseSensitive, pattern);
        return new PathMatcherBackedSpec(partialMatchDirs, pathMatcher);
    }

    private static PathMatcher compile(boolean caseSensitive, String pattern) {
        if (pattern.length() == 0) {
            return END_OF_PATH_MATCHER;
        }

        // trailing / or \ assumes **
        if (pattern.endsWith("/") || pattern.endsWith("\\")) {
            pattern = pattern + "**";
        }
        String[] parts = pattern.split("\\\\|/");
        return compile(parts, 0, caseSensitive);
    }

    private static PathMatcher compile(String[] parts, int startIndex, boolean caseSensitive) {
        if (startIndex >= parts.length) {
            return END_OF_PATH_MATCHER;
        }
        int pos = startIndex;
        while (pos < parts.length && parts[pos].equals("**")) {
            pos++;
        }
        if (pos > startIndex) {
            return new GreedyPathMatcher(compile(parts, pos, caseSensitive));
        }
        List<PatternStep> steps = new ArrayList<PatternStep>(parts.length - startIndex);
        while (pos < parts.length && !parts[pos].equals("**")) {
            steps.add(PatternStepFactory.getStep(parts[pos], caseSensitive, pos + 1 == parts.length));
            pos++;
        }
        return new FixedStepsPathMatcher(steps, compile(parts, pos, caseSensitive));
    }

    private static class PathMatcherBackedSpec implements Spec<RelativePath> {
        private final boolean partialMatchDirs;
        private final PathMatcher pathMatcher;

        public PathMatcherBackedSpec(boolean partialMatchDirs, PathMatcher pathMatcher) {
            this.partialMatchDirs = partialMatchDirs;
            this.pathMatcher = pathMatcher;
        }

        public boolean isSatisfiedBy(RelativePath element) {
            if (element.isFile() || !partialMatchDirs) {
                return pathMatcher.matches(element.getSegments(), 0, element.isFile());
            } else {
                return pathMatcher.isPrefix(element.getSegments(), 0, element.isFile());
            }
        }
    }
}
