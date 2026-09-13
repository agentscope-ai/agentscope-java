# Namespace and work authorization

> 2026-09-09：在账号持续可用的前提下，已扩展用户组、资源权限、依赖授权、申请审批与跨空间模板导入。后续实现与管理入口见 [Namespace 权限与配套资源管理](resource-permissions-management.md)。

Implementation baseline: `b5dd9dc2f` on `codex/service-permissions`. This includes a
separate snapshot of the main checkout's existing workflow changes. Only commits
after that baseline belong to the permissions change.

## Main checkout integration (2026-09-08)

The permissions increment from `9c4111198` is now integrated into the working tree
at `/Users/ken/agentscope-2/agentscope-java`, on `agentscope-service-v5`. Existing
uncommitted Workflow, MCP and managed-runtime work was preserved by a three-way
file merge against `b5dd9dc2f`; the baseline snapshot was not reapplied. No new
merge commit was created, and concurrent staging by other work was preserved.

Conflict resolutions retain the newer Workflow parent-node filter together with
Issue visibility filtering, and retain managed resource fingerprinting, credential
rotation, runtime cleanup and definition materialization together with per-session
paths and read-only shared memory. Frontend assets were rebuilt from the combined
sources.

Validation on the combined main checkout passed: all Go tests, a fresh PostgreSQL
store/product suite, 116 frontend tests, the production-console permissions browser
test, Go binary build, and the service Java reactor verify with builder-web tests.
Java formatting was limited to the three files involved in this integration.
No running service or production database was changed.

## Contract

- A namespace is the logical ownership boundary. The platform provisions a global
  `default` namespace at startup and selects it when the user has no explicit
  preference. Every account can use, configure and operate this namespace, while
  membership management and private-work auditing remain restricted. Each account
  also receives a personal namespace; administrators can provision shared namespaces.
  Runtime file workspaces and console navigation areas are separate concepts.
- Namespace membership is read from durable storage on every request. Roles are
  viewer, member, developer, operator, admin and auditor. Namespace admins manage
  membership and definitions; only an explicit auditor role grants access to all
  private business data. Platform administration alone does not grant that access.
- Issue access is private, shared with selected readers/contributors, or visible
  to namespace members. New user work defaults to private. Existing Issue rows
  retain their previous namespace visibility during migration. Children inherit
  the root Issue's current policy; changing a root policy also changes access to
  comments, tasks, runs, sessions and artifacts.
- Resource discovery, invocation, configuration, mutation and administration are
  separate actions. Calling an Agent does not grant access to other callers' work.
- Cross-namespace assignment remains denied in this implementation. Both source
  data export policy and explicit target sharing are required before that boundary
  can be opened; membership in two namespaces never implies permission to bridge.
- Task tokens remain bound to persisted Task/Attempt/generation. Human resource
  access never broadens the permissions of a task token.
- Issue, task, run, session, approval, inbox and automation list filtering happens before pagination. Individual object routes resolve persisted
  scope; query/body scope cannot override it. Event delivery checks current access.

## Delivery checks

Exercise namespace membership/revocation, private/shared work, inherited children,
execution and artifact reads, forged scope and references, pre-pagination filters,
namespace management concurrency, and existing Task-token regression tests against
memory and PostgreSQL stores. Build and test the console and affected Go modules.

## Rollout

The platform-managed global `default` namespace and each authenticated user's
personal namespace appear together in the namespace selector. Other existing
namespaces require explicit membership provisioning by a platform admin. Existing
global console roles remain navigation hints; namespace roles are the resource authority. Static-token
development mode and Kubernetes SAR retain their existing authentication boundary.
Private workload isolation also requires separate runtime memory/file mounts;
shared provider infrastructure is not a confidentiality boundary against its host
administrator. No cross-namespace data-sharing guarantee is made by this release.

## Console and API

The sidebar namespace selector is populated by `GET /api/v1/me/scope` and
`GET /api/v1/me/namespaces`. Arbitrary URL/local-storage scope values are discarded.
Requests carry `X-AgentScope-Tenant` and `X-AgentScope-Namespace`; explicit query,
body and persisted-object scope must agree. Switching scopes clears cached data
and remounts forms.

`/settings/namespaces` manages shared namespaces and membership. The legacy
`/work/permissions` route redirects to the current authorized namespace. See
[Account and namespace management](account-namespace-management.md) for the
Users, Profile, lifecycle and audit increment implemented on 2026-09-08.

- `POST /api/v1/namespaces`: platform admin provisions a namespace; no implicit
  ownership claim by an ordinary account over pre-existing resources.
- The global `default` namespace is platform-managed and cannot be transferred,
  archived or edited through namespace membership APIs.
- `GET/PUT /api/v1/namespaces/:name`: namespace admin or platform admin;
  membership writes require the current `version` and are audited transactionally.
- `PUT /api/v1/issues/:id/access`: root human creator only, expected `version`,
  and `{access: {mode: "private" | "shared" | "namespace", members: {...}}}`.
  Selected collaborators must also be namespace members. Roles are `reader` or
  `contributor`. Child Issues cannot override root sharing.

Namespace `admin` does not include `auditor`. A shared namespace owner may receive
an explicit auditor assignment through the membership editor. Personal membership
is fixed. A reader can inspect authorized work; mutation additionally requires a
namespace execution role and a contributor/creator grant.

Automation rules and diagnostics are private to their creator (or readable by an
explicit auditor). Before a registered-namespace automation dispatches new work,
it rechecks its creator's current execution role and existing Issue target access.
Integration delivery payloads require an auditor, except for the automation owner.
Infrastructure dead-letter replay remains a service-identity operation.

## Runtime behavior

Java managed agent caches, working directories and staged definition files are
session-specific. Task credentials retain their attempt/generation cache fence.
User attachments are no longer copied back into the shared Agent definition.
Shared memory mounts are read-only knowledge; mutable working files belong to the
session. Previously configured Agent working paths remain definition-management
paths and are not reused as a common mutable directory for every caller.

This is application-level isolation. A runtime with unrestricted host shell/file
access still needs an OS/container sandbox to enforce filesystem confidentiality;
an external provider must enforce its own session and credential isolation.
Infrastructure administrators and static/internal service tokens remain trusted.

## Compatibility and release procedure

1. Back up the control-plane database and apply migration `0108`. It preserves
   existing Issue visibility as `namespace`, while new Issue rows default private.
2. Confirm the platform-created global `default` namespace is visible beside each
   user's Personal namespace. Provision other existing namespaces through the
   platform admin page and add their account IDs/roles. Ordinary console accounts
   no longer gain resource access from the old global navigation roles.
3. Test a member and a developer in the same namespace, including sharing and
   revocation. Shared product resources use owner partition
   `namespace:<tenant>:<name>`; existing account-owned product definitions retain
   their owner partition. Existing personal resources are not silently transferred
   into a shared namespace.
4. Keep existing service/Kubernetes authentication configuration. This release
   changes console account authorization, not the trust model of service tokens.

Namespace overview does not reuse tenant-wide caches or expose private session
rankings. Legacy fleet ranking metrics are omitted from the console's scoped
summary until they support the same data policy; `usageUnavailable` marks its
unavailable usage counters. Session/command diagnostics only return authorized
sessions. Command/dead-letter diagnostic pages may return fewer rows after access
filtering; business work list pagination is filtered in the repositories.

The working branch includes an initial snapshot of pre-existing uncommitted
workflow changes. To integrate, apply only the permissions commit(s) after
`b5dd9dc2f` to the main checkout; do not reapply the baseline snapshot. During isolated development, no main
checkout, running service or production database was changed. Main checkout
integration is recorded above.

## Verification (2026-09-08)

- `go test ./...`: all 37 packages containing tests passed; command packages built.
- PostgreSQL 17, isolated local test database: complete store and product suites
  passed; membership CAS, inheritance, pagination, run/session and approval access
  were additionally exercised through the common memory/PostgreSQL contract.
- Namespace HTTP regressions: private work, collaborators, live revocation,
  artifacts, events, forged scope, nested foreign targets and overview isolation.
- Live account test: a still-valid JWT loses platform roles immediately after a
  role edit and is rejected after account deletion.
- `npm test`: 24 files, 100 tests passed. TypeScript and production Vite build passed.
- Production-console Playwright test passed: namespace fallback, membership save,
  transport scope and clearing unsaved forms when switching namespaces.
- Maven reactor `verify` with `-Dtest=io.agentscope.builder.web.**.*Test`:
  25 service-common tests and 55 service-dataplane tests passed; dependencies were
  built and formatting checked. This was not a full-repository `clean verify`.
  The pre-existing HITL lease-loss test failed once under concurrent build load
  (`OWNER_UNAVAILABLE` versus `CONTINUATION_LOST`); its isolated rerun and the full
  service-test rerun both passed without modifying that test or production code.

PostgreSQL's complete contract suite assumes an empty database: use a fresh test
DB for each full invocation, rather than reusing its prior fixed-name fixtures.
The added NamespaceAccess contract uses unique tenants and can run independently.
