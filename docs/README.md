# AgentScope Java documentation · Mintlify

This directory is the complete Mintlify source for the AgentScope Java site. It includes
legacy v1, stable 2.0 and dev documentation in English and Chinese, the landing pages, SDK guides,
integrations, blog posts, and community pages. Java/Maven is not required
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

`npm run check` verifies all published versioned pages are reachable from navigation, every page
has a title, local images/links/anchors exist, and redirects have valid targets. It follows
`.mintignore`, as Mintlify does. Public navigation and links to excluded files fail validation.
Keep this directory focused on user guides and reference documentation. Research and development
plans, implementation/test progress, validation reports and data used solely for test acceptance
belong outside the source repository, under `~/agentscope-3/docs`. Do not add them here as
unpublished drafts or hide them with `.mintignore`.
`npm run validate` also runs Mintlify's strict build validation. The GitHub Actions
workflow runs these checks on documentation PRs and pushes to `main` or `Mintlify`.

## Documentation versions

Mintlify's native version selector is configured under
`navigation.languages[].versions` in `docs.json`. Both languages offer:

| Selector | Source and scope | Page paths |
| --- | --- | --- |
| **2.0** (default) | Published user documentation from the `v2.0.4` tag | `v2/{en,zh}/` |
| **dev** | Current development APIs and user guides | `dev/{en,zh}/` |
| **v1** | Legacy documentation | `v1/{en,zh}/` |

Home, SDK/Harness, Integration and JEV are versioned. In 2.0, JEV has its own tab. In dev, its English and Chinese guides live under Harness → JEV, in `dev/{en,zh}/docs/harness/jev/`. Blog and Community
remain shared under `v2/{en,zh}/blogs/` and `v2/{en,zh}/community/`; dev navigation
omits articles about removed modules. Their content is not a release snapshot,
but repository source links still use the appropriate release tag or commit.
The root redirect `/` opens `/v2/en/intro`; each language's 2.0 entry explicitly
sets `default: true`.

Edit `dev/` when changing the current source APIs. Keep 2.0 consistent with the
`v2.0.4` code; only apply corrections appropriate to that release. Its SDK quickstart
uses Maven version `2.0.4`, and repository source links are pinned to the tag.
Historical guides retain the modules and availability limitations documented at
that tag, even when those modules are no longer part of the current source tree.
Release-specific images and downloads are preserved under `v2/assets/`. Current
dev pages use `imgs/` and `examples/`; refreshing them does not alter stable examples.

To publish a future 2.1 release:

1. Snapshot the released user-facing pages from `dev/` into `v2.1/{en,zh}/` and
   pin examples, dependencies, source links and changed assets to that release.
   Do not copy development plans, test reports or acceptance-only data.
2. Add a `"version": "2.1"` entry to both language arrays. Update its page paths,
   internal links, `en_link` and `zh_link` to `v2.1/...`. Keep Blog and Community
   pointing to their shared pages. Leave dev available for ongoing development.
3. Add `/v2.1`, `/v2.1/en` and `/v2.1/zh` landing redirects and any legacy `.html`
   aliases. Preserve existing stable URLs. If 2.1 becomes the default, move
   `default: true` to it in both languages and update the `/` redirect.
4. Run `npm test`, `npm run validate` and `npm run broken-links`, then publish
   through the branch connected to Mintlify. One project hosts all versions.

The local checker discovers `dev`, `v1`, `v2`, `v2.1`, `v3` and other numeric
version directories, including unlisted pages. It checks translation links within
each version. Do not publish an empty version entry ahead of its documentation.

See [Mintlify's version navigation reference](https://www.mintlify.com/docs/organize/navigation#versions)
for the selector and default-version fields.

## Authoring

### Analytics

`baidu-analytics.js` loads Baidu Tongji globally through Mintlify's custom JavaScript
support, using site ID `9cc2f979c09fd25f67b57f89237e28a2`. It also records page changes
through `mintlify:navigate` and the History API for older clients, since client-side
navigation does not reload the script. Repeated notifications for the same URL are ignored.
Keep this integration in the global script rather than copying it into individual pages.

### Content standards

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

### Site conventions

- `docs.json` is the single source of truth for navigation, versions, languages,
  branding and redirects. Default navigation is English 2.0, served from `v2/`.
- `v1/{en,zh}/`, `v2/{en,zh}/` and `dev/{en,zh}/` contain versioned pages. `.md` files support
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
  `/dev/zh/docs/quickstart` for dev. Put current images under `imgs/` and reference `/imgs/...`; preserve stable assets under their version.
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

4. Open the URL provided by **Overview** and verify both languages, all versions,
   landing page interactions, diagrams, and links. The hosted build
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
