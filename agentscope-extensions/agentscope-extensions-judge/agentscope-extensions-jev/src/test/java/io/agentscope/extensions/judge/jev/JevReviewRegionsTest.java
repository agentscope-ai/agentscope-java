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

package io.agentscope.extensions.judge.jev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.judge.jev.review.JevReviewInput;
import io.agentscope.extensions.judge.jev.review.ReviewRegions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JevReviewRegionsTest {
    @Test
    void sourceRegionsHaveDeterministicBoundariesAndNoPhantomFinalLine() {
        var regions = ReviewRegions.source("one\ntwo\nthree\n", 2);
        assertEquals(2, regions.size());
        assertEquals(
                new ReviewRegions.Region("R1", 1, 2, ReviewRegions.Side.NEW, "one\ntwo"),
                regions.get(0));
        assertEquals(3, regions.get(1).startLine());
        assertEquals(3, regions.get(1).endLine());
        assertTrue(ReviewRegions.source(" ", 80).isEmpty());
    }

    @Test
    void parsesMultipleHunksAndDeletionUsesOldSide() {
        var regions =
                ReviewRegions.patch(
                        "@@ -2,2 +2,2 @@\n"
                                + " context\n"
                                + "-old\n"
                                + "+new\n"
                                + "@@ -10,2 +10,0 @@\n"
                                + "-deleted\n"
                                + "-line\n");
        assertEquals(2, regions.size());
        assertEquals(2, regions.get(0).startLine());
        assertEquals(3, regions.get(0).endLine());
        assertEquals(ReviewRegions.Side.OLD, regions.get(1).side());
        assertEquals(10, regions.get(1).startLine());
        assertEquals(11, regions.get(1).endLine());
    }

    @Test
    void rejectsMalformedBinaryAndCombinedDiffsInsteadOfInventingLineNumbers() {
        for (String patch :
                List.of(
                        "@@ -1,2 +1,1 @@\n-old\n+new",
                        "Binary files differ",
                        "@@@ -1 +1 +1 @@@\n+x",
                        "@@ -0,1 +0,1 @@\n-x\n+y",
                        "@@ -1 +2147483647,2 @@\n-x\n+y\n+z"))
            assertThrows(IllegalArgumentException.class, () -> ReviewRegions.patch(patch));
    }

    @Test
    void inputRejectsDuplicatePathsTraversalAndDanglingTestReferences() {
        for (String path : List.of("../source.py", "/source.py", "a/../b", "a//b", "C:\\a", "a\nb"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new JevReviewInput.File(path, "code", List.of()));
        var file = new JevReviewInput.File("source.py", "code", List.of());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new JevReviewInput(
                                "r", JevReviewInput.Mode.CODEBASE, List.of(file, file), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new JevReviewInput(
                                "r",
                                JevReviewInput.Mode.CODEBASE,
                                List.of(
                                        new JevReviewInput.File(
                                                "source.py", "code", List.of("test.py"))),
                                List.of()));
    }

    @Test
    void snapshotCopiesCallerCollections() {
        var related = new ArrayList<String>();
        related.add("test.py");
        var files = new ArrayList<JevReviewInput.File>();
        files.add(new JevReviewInput.File("source.py", "code", related));
        var input =
                new JevReviewInput(
                        "r",
                        JevReviewInput.Mode.CODEBASE,
                        files,
                        List.of(new JevReviewInput.TestEvidence("test.py", "test")));
        files.clear();
        related.clear();
        assertEquals(1, input.files().size());
        assertEquals(List.of("test.py"), input.files().get(0).relatedTests());
    }
}
