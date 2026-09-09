# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    'check_service_docs', Path(__file__).resolve().parents[1] / 'check_service_docs.py'
)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class SourceChecksTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.docs = Path(temporary.name)
        self.tracked = set()
        self.toc = {
            'root': 'v2/en/service/index',
            'parts': [{'chapters': [{'file': 'v2/zh/service/index'}]}],
        }
        self.add_page('v2/en/service/index.md')
        self.add_page('v2/zh/service/index.md')

    def add_page(self, name):
        path = self.docs / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('# Page\n')
        self.tracked.add(name)

    def test_local_page_not_tracked_is_rejected_before_ci(self):
        self.tracked.remove('v2/en/service/index.md')
        _, errors = checker.check_sources(self.docs, self.toc, self.tracked)
        self.assertEqual(len(errors), 1)
        self.assertIn('not tracked by Git (missing in CI): v2/en/service/index.md', errors[0])

    def test_missing_page_is_reported_even_when_git_lists_it(self):
        (self.docs / 'v2/zh/service/index.md').unlink()
        _, errors = checker.check_sources(self.docs, self.toc, self.tracked)
        self.assertEqual(errors, ['TOC source missing: v2/zh/service/index.md'])

    def test_notes_outside_published_toc_are_not_required_as_html(self):
        self.add_page('v2/zh/service/internal-note.md')
        sources, errors = checker.check_sources(self.docs, self.toc, self.tracked)
        self.assertEqual(errors, [])
        self.assertEqual([p.name for p in sources['zh']], ['index.md'])

    def test_translation_names_must_match_not_just_page_counts(self):
        self.add_page('v2/zh/service/another-page.md')
        self.toc['parts'][0]['chapters'][0]['file'] = 'v2/zh/service/another-page'
        _, errors = checker.check_sources(self.docs, self.toc, self.tracked)
        self.assertEqual(len(errors), 1)
        self.assertIn("English only=['index']", errors[0])
        self.assertIn("Chinese only=['another-page']", errors[0])

    def test_preflight_covers_legacy_toc_pages_too(self):
        self.toc['parts'][0]['chapters'].append({'file': 'v1/en/intro'})
        _, errors = checker.check_sources(self.docs, self.toc, self.tracked)
        self.assertEqual(errors, ['TOC source missing: v1/en/intro.md'])


if __name__ == '__main__':
    unittest.main()
