#!/usr/bin/env python3
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

"""Compile the actual Java blocks of rewritten pages, supplying only documented host objects.

This is an internal documentation check; it never executes snippets or contacts a model.
Extend CONFIG only after reviewing the variables and intended sequence of each page.
"""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CONFIG = {
    'client': '',
    'tool-guard-api': 'Model model, Toolkit toolkit, io.agentscope.core.permission.PermissionContextState permissionContext',
    '../operations': '',
    'phase-routing-api': 'JevClient client, Model originalModel, Toolkit toolkit, Model registeredModel, long estimatedInputTokens, int outputReserve, int consecutiveFailures, java.util.List<io.agentscope.core.message.Msg> messages, java.util.function.BiFunction<RuntimeContext, io.agentscope.core.middleware.ModelCallInput, reactor.core.publisher.Mono<io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Snapshot>> directory',
    'browser-execution-api': 'JevClient client, Model model, String userId, String sessionId, String tabId, String chromeExecutable, String authorizedStartUrl, String authorizedPolicyUrl, java.util.function.Function<io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Request, io.agentscope.extensions.judge.jev.browser.JevBrowserSession> openSession, io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Verifier verifier',
    'code-review-api': 'JevClient client, RuntimeContext context, Model model',
    'knowledge-adapter-api': 'JevClient client, io.agentscope.core.rag.Knowledge existingKnowledge, java.util.function.BiPredicate<RuntimeContext, io.agentscope.core.rag.model.Document> canRead, Toolkit toolkit, RuntimeContext ctx',
    'evidence-pipeline-api': 'JevClient client, RuntimeContext context, Model model',
    'draft-pipeline-api': 'JevJudge judge',
    'content-guardrail-api': 'JevClient client, Model model, Toolkit toolkit',
    'answer-refinement-api': 'JevClient client, Model model, Toolkit toolkit',
    'supervision-api': 'Model model',
    'supervision-evidence': 'EvidenceRepository evidenceRepository, String userId, String sessionId',
    'task-review': 'JevJudge judge',
    'rag-api': 'RuntimeContext ctx, JevCandidateSelector selector, JevJudge judge',
    'metric-definitions': 'JevClient client',
    'trace-evaluation-api': 'JevClient client, Model model, Toolkit toolkit',
    'context-compaction-api': 'JevClient client, Model model, java.nio.file.Path archiveRoot',
    'context-archive': 'java.nio.file.Path archiveRoot, String userId, String agentId, String sessionId, String archiveReference',
    'application-api': 'RuntimeContext ctx',
    'context-planner-api': 'RuntimeContext ctx, JevCandidateSelector selector',
    'memory-api': 'JevJudge judge',
    'support-api': 'RuntimeContext ctx, JevCandidateSelector selector, JevJudge judge',
    'team-routing': 'RuntimeContext ctx, JevCandidateSelector selector',
    'browser-proposals': 'RuntimeContext ctx, JevCandidateSelector selector, BrowserHost browser',
    'judge-api': 'JevClient client',
    'evaluator-api': 'JevClient client',
    'tool-selection-api': 'JevClient client, Model model, Toolkit toolkit',
    'model-routing-api': 'JevClient client, Model fastModel, Model strongModel, Model originalModel, Toolkit toolkit',
    'harness-runtime': 'JevClient client',
}
COMMON = '''import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.application.JevCandidateSelector;
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--classpath-file', type=Path, default=Path('/tmp/jev-examples-cp.txt'))
    args = parser.parse_args()
    classpath = str(ROOT / 'agentscope-examples/jev/target/classes') + ':' + args.classpath_file.read_text().strip()
    with tempfile.TemporaryDirectory(prefix='jev-guide-snippets-') as temporary:
        files = []
        for name, parameters in CONFIG.items():
            page = ROOT / 'docs/v2/zh/jev/guides' / (name + '.md')
            blocks = re.findall(r'```java\n(.*?)\n```', page.read_text(), re.S)
            if not blocks:
                raise ValueError(f'No Java blocks: {name}')
            body = '\n'.join(blocks)
            imports = re.findall(r'^import .+?;$', body, re.M)
            body = re.sub(r'^import .+?;\n?', '', body, flags=re.M)
            classname = 'Guide_' + name.replace('-', '_').replace('../', '')
            source = COMMON + '\n'.join(imports) + f'\nclass {classname} {{\n'
            source += 'interface BrowserHost { String currentPageVersion(); }\n'
            source += 'interface EvidenceRepository { reactor.core.publisher.Mono<io.agentscope.extensions.judge.jev.supervision.SupervisionEvidence> readAuthorizedSnapshot(io.agentscope.extensions.judge.jev.supervision.JevSupervisionMiddleware.EvidenceRequest request); }\n'
            source += f'void example({parameters}) throws Exception {{\n' + body + '\n}\n}\n'
            file = Path(temporary) / (classname + '.java')
            file.write_text(source)
            files.append(str(file))
        completed = subprocess.run(['javac', '--release', '17', '-cp', classpath, '-d', temporary, *files])
        if completed.returncode:
            raise SystemExit(completed.returncode)
    print(f'Compiled actual Java blocks from {len(CONFIG)} rewritten pages; no snippets executed.')


if __name__ == '__main__':
    main()
