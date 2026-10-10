# AgentScope Java documentation · Mintlify

This directory is the complete Mintlify source for the AgentScope Java site. It includes
v1 and v2 documentation in English and Chinese, the landing pages, SDK guides,
Service, integrations, blog posts, and community pages. Java/Maven is not required
to preview or publish the documentation.

## Local development

Use Node.js 22 LTS and run these commands from `docs/`:

```bash
npm ci
npm run dev
```

Open http://localhost:3000. Keep `package-lock.json` committed so local development
and CI use the same CLI and parser versions. The current pinned CLI is `mint`, not
the legacy `mintlify` npm package.

Before submitting documentation changes:

```bash
npm test
npm run validate
npm run broken-links
```

`npm run check` verifies all v1/v2 pages are reachable from navigation, every page
has a title, local images/links/anchors exist, and redirects have valid targets.
`npm run validate` also runs Mintlify's strict build validation. The GitHub Actions
workflow runs these checks on documentation PRs and pushes to `main` or `Mintlify`.

## Refresh Service screenshots

Agent and task management guides, including resource pages, share screenshots under `imgs/service/`. They are
captured from the current React console with synthetic API fixtures; names, task
results, channel status and credential metadata are examples, not a live deployment
or an end-to-end execution result. Keep this distinction in each page's caption.

From the repository root, with Node.js 22 installed:

```bash
cd agentscope-service/frontend
npm ci
npx playwright install chromium
npm run docs:screenshots
```

The script starts Vite on `127.0.0.1:5188`, opens a fresh Chromium context, captures
13 images of 12 pages at 1440 pixels wide (960 or 1120 pixels high, depending on
the page), then stops both processes. Port 5188 must be available.
It intercepts backend API calls with fixed documentation fixtures, so PostgreSQL,
Java services, model credentials and an existing console login are unnecessary.
It does not read or write your running Service database. Screenshot text retains
the current console's English control labels; both documentation languages reuse
the same images with translated explanations and alternative text.

Capture steps and readiness assertions live in
`agentscope-service/frontend/scripts/docs/capture.mjs`; example API responses live
beside it in `fixtures.mjs`. Update these when the UI or API changes. An unexpected
API call, page exception or visible alert fails the capture. Images are staged in
a temporary directory and copied to the documentation only after all captures
succeed. Review every image for loading states, clipping and readable text before
submitting it; never substitute screenshots containing real credentials or private
work. Playwright/Chromium and platform font updates may change the rendered pixels.

After capture, run the documentation checks above and inspect the Chinese and
English guides with `npm run dev` from `docs/`. Commit the PNG files with the
corresponding guides and script changes; do not commit browser traces or caches.

## Authoring

### Service content and scenario standards

The primary Service journey is **self-host deployment → first Managed Agent →
capability configuration → Session API application integration → collaboration
and operations**. Managed Agents use the AgentScope HarnessAgent core. Explain
whole-platform self-hosting separately from a self_hosted tool Worker and a Hosted
Runtime Host. Existing deployments can skip installation. Preserve standalone
External/Hosted onboarding while introducing them as collaboration extensions.

Use Session, Turn and Events as the sole application execution model for Agents,
Teams and Workflows. Keep three Getting started pages: overview, deployment and
first Managed Agent. Explain core concepts in the overview. Application credentials
belong to Applications and grant explicit target resources. Do not reintroduce a
separate Endpoint publication path. Write connected paragraphs explaining the
meaning, reason and next action instead of terse lists of features or nouns.
Keep one guide per user task: tool permissions belong with tools, Skills with
Workspaces, budgets with sessions, and Webhooks with events. Console operations
share one guide; SDK and runtime parameters stay in focused references. Avoid
restoring separate overview/capability/execution pages for every Agent type. Moving a section must
preserve its existing route/anchor and link to the new guide. Keep both languages
and navigation aligned. Keep the five onboarding pages directly visible and group
advanced topics in collapsible navigation. Document current limits instead of implying parity
with a referenced hosted product.

Service guides should explain the user goal, prerequisites, exact console or API
steps, observable success criteria, failure recovery, and the next relevant guide.
Reference pages should describe defaults, allowed values, configuration scope,
when changes take effect, and limits that affect the documented operation. Check
these against the current console, control plane, and runtime implementation.
Avoid adding identical sections to every page when a precise cross-link suffices.

Write explanatory prose for readers learning the product. Each paragraph should
develop one clear idea through complete sentences with explicit subjects,
conditions, and causal connections. Introduce a term through its purpose and
relationship to the current task; explain when an action applies, why it is needed,
and how to interpret its result. Make limits and failure states actionable by
explaining their consequences and the next step. Do not compress explanations into
noun lists, semicolon-separated conclusions, or notes that require implementation
knowledge. Tables remain useful for parameter comparisons and checklists, while
conceptual relationships and operating logic belong in connected prose. Follow
the material's natural structure rather than forcing every section into the same
template or requiring an example to carry every explanation. Apply this standard
to both language versions and future edits.

Scenario tutorials live in `v2/{en,zh}/service/cases/`; downloadable inputs live in
`examples/service/`. Code and JSON inputs use a final `.txt` extension so Mintlify
serves them as static downloads; guides specify the executable or JSON filename
to use after saving. Each case must have fixed inputs, explicit acceptance criteria,
and a distinction between expected output and observed execution. Keep both
languages and the functional-guide links aligned. The use-case entry page organizes
application patterns around triggers, API calls, delivery, and acceptance: in-product
file generation, code repair, document verification, conversations, recurring work,
and specialist services called by other Agents. Each pattern has its own detailed
bilingual tutorial and fixed request data. Managed,
External, Hosted, Team, and Workflow remain implementation choices. Distinguish
industry evidence, expected fixture outputs, and actual AgentScope execution records.
Retired team-oriented tutorial URLs redirect to the corresponding new case or the
use-case index; do not restore those tutorials as a second scenario taxonomy.

From `docs/`, validate the local code and JSON fixtures with Python 3 and JDK 17+:

```bash
python3 scripts/check-service-examples.py
```

The incident-repair Java fixture intentionally fails three of five acceptance checks.
The checker compiles it in a temporary directory, verifies the baseline, applies a
reference repair only in that copy, and checks the repaired result. It also checks
six API request fixtures, source versions, the document-verification contradiction,
conversation ownership data, and Bash syntax in all twelve case pages. It does not
execute network commands or run models, GitHub, business APIs, or Service. Do not
fix the published starting code or commit generated classes and logs.

For an actual scenario walkthrough, record Service and SDK/provider versions,
configuration choices, input data, Session/Turn and diagnostic Issue/Run IDs, artifacts, observed
results, deviations, and cleanup. Include the idempotency/filtering or failure
branch described by the case. Preserve logs from real execution; generated prose
and fixture-based screenshots do not establish end-to-end success.

### Site conventions

- `docs.json` is the single source of truth for navigation, versions, languages,
  branding and redirects. Default navigation is English v2.
- `v1/{en,zh}/` and `v2/{en,zh}/` contain the existing pages. `.md` files support
  Mintlify's MDX components; keeping the extension preserves GitHub source links.
- Keep English and Chinese documentation aligned. Add pages to the corresponding
  language/version/tab in `docs.json`.
- Begin each page with YAML frontmatter containing `title`; add `description` when useful.
- Use `<Note>`, `<Tip>`, `<Warning>`, `<Tabs>`, `<Tab>`, `<CardGroup>`, `<Card>` and
  `<Accordion>` for interactive content. Put Markdown inside components on its own
  lines with blank lines around it.
- Use fenced `mermaid` blocks for diagrams. MyST/Sphinx directives such as
  `:::{note}`, `{raw}` and `{mermaid}` are no longer supported.
- Use root-relative internal links without file extensions, for example
  `/v2/zh/service/quickstart`. Put images under `imgs/` and reference `/imgs/...`.
- Put literal Java generics, placeholders, and shell expressions in code spans or
  fenced code blocks so MDX does not interpret them as JSX or JavaScript.
- Landing page styles and interactions are scoped in `style.css` and `landing.js`.
  Mintlify automatically loads these assets. Do not restore Sphinx's global CSS/JS.
- `.mintignore` excludes build caches and authoring tools from the published site.

## Connect Mintlify hosting

1. Create a site at https://www.mintlify.com/start and connect GitHub.
2. Install the Mintlify GitHub App in `agentscope-ai`, granting access to
   `agentscope-java`. A repository administrator can do this if organization policy
   permits; otherwise an organization owner must approve the installation.
3. In **Git Settings**, select:

   | Field | Value |
   | --- | --- |
   | Organization | `agentscope-ai` |
   | Repository | `agentscope-java` |
   | Branch | `Mintlify` to deploy this migration branch; `main` after merging |
   | Documentation directory | `docs` |

4. Open the URL provided by **Overview** and verify both languages, both versions,
   landing page interactions, Service pages, diagrams, and links. The hosted build
   may expose configuration differences that local preview cannot verify.
5. Add `java.agentscope.io` in **Custom domain setup** when ready to replace the
   existing site. Add the exact TXT verification records shown in the dashboard;
   wait for verification and TLS provisioning before changing the CNAME to
   `cname.mintlify.builders`. Cloudflare-proxied domains require the alternate flow
   in the official custom-domain guide.
6. Future pushes to the configured deployment branch trigger Mintlify publication.
   Changing the local branch does not change the branch selected in Mintlify.

The repository workflow validates content; the Mintlify GitHub App handles hosting.
There is no GitHub Pages deployment, `_build/html` artifact, or documentation deploy
secret in this workflow. Changing these files does not install the GitHub App,
create a Mintlify account, or modify DNS. Existing GitHub Pages content stays online
until its hosting or DNS is changed separately.

## Existing links

`docs.json` redirects the previous `.html` page URLs to extensionless routes. It also
preserves the pre-v1 `/en/...html` and `/zh/...html` aliases from the former redirect
generator, and supplies version/language landing routes. Keep these redirects when
adding new navigation so links from releases and external websites continue working.
Ten wildcard fallbacks follow the exact mappings: `/en/:slug*` and `/zh/:slug*`
preserve paths under v1, while the `harness`, `multi-agent`, `quickstart`, and `task`
prefixes insert the v1 `docs` directory. For example, `/zh/harness/memory` redirects
to `/v1/zh/docs/harness/memory`. Keep specific rules before broader fallbacks.
These rules use permanent redirects (308); set `permanent: false` for temporary
redirects (307). Unknown article paths still return 404 instead of going to the homepage.
The local checker supports prefix wildcards in the `/:slug*` form and checks their
destination pages, page conflicts, and redirect loops. Do not add a site-wide
`/:slug*` fallback that would also match the new routes.
The production domain remains `java.agentscope.io`. All redirects explicitly set
`permanent: true`. Bind `java.agentscope.io` in the Mintlify dashboard and apply the
DNS records it provides for these rules to handle incoming links. Keep the same
hostname; no cross-domain redirect is needed. Deploying on a different domain alone
does not redirect the old domain.
Mintlify supplies its own search and AI-readable documentation endpoints.

Official references:
- https://www.mintlify.com/docs/quickstart
- https://www.mintlify.com/docs/deploy/github
- https://www.mintlify.com/docs/organize/navigation
- https://www.mintlify.com/docs/customize/custom-domain

## Helm repository route

`chickenlj/helm-charts` is the independent public repository for packaged Charts.
Its Pages workflow serves `index.yaml`, immutable archives and versioned deployment
examples at `https://chickenlj.github.io/helm-charts`. Run its `publish.yml` workflow
with a Chart version to import the exact upstream GitHub Release asset; publication
checks its SHA256 and preserves older index entries.

`docs.json` redirects `/helm/index.yaml` to that index and `/helm` to its landing
page, with temporary redirects (`permanent: false`). Archive URLs are absolute,
so the intended alias only needs to redirect the index request. Production
verification after merging found that `/helm` redirects but `/helm/index.yaml`
returns 404, so installation guides use the verified direct Pages URL:
`helm repo add agentscope https://chickenlj.github.io/helm-charts`. Do not announce
the website alias until both `helm repo add` and a pinned `helm pull` pass against
it. The local Mintlify redirect check alone does not establish production behavior.
Do not assign `java.agentscope.io` as the standalone
repository's Pages custom domain, which would replace the documentation site.
