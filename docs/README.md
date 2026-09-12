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

## Authoring

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
