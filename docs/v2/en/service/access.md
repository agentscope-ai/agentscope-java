---
title: Accounts, Namespaces and permissions
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Platform administrators manage accounts and spaces. Resource use and work visibility also depend on Namespace and work-specific permissions.

## Initialize accounts

Release deployments use a configurable bootstrap administrator rather than fixed demo passwords. Change its password in Profile after the first sign-in. Create everyday accounts in Management → Users and grant roles appropriate to their responsibilities.

Profile manages display names, passwords, login sessions, personal connections and subscriptions. A password reset or account suspension can require a new sign-in.

## Namespaces

Use Management → Namespaces to manage shared spaces, members and roles. Users can inspect their effective access; owners and administrators manage members and resources within their authorization. A Namespace is distinct from a file Workspace.

When sharing an Agent, check dependent resource grants too. Visibility of an Agent, Team or Workflow does not make every resulting Issue or Session visible to the same people.

## Diagnose access failures

Check the signed-in account, selected Namespace, resource ownership, current membership and whether the work itself is private. Refresh after grants change. On a version conflict, read the latest configuration before editing again.

Platform administration does not automatically grant access to all private work. Auditing requires the appropriate space permissions. Do not distribute internal service tokens as a substitute for user authorization.

## Example: share the presales team

Use the [presales team case](/v2/en/service/cases/presales-team) with two accounts: the resource owner and an ordinary member. An administrator-only walkthrough cannot validate member access.

1. The owner configures the Agent and its Memory Store in the intended Namespace, checking ownership and required grants.
2. The member selects that Namespace, checks whether the Agent is available, and starts their own Chat requesting `product/capabilities.md`.
3. Inspect tool records for a successful read. If the Agent is visible but knowledge is unavailable, check dependent resources and bindings.
4. Check resource use, configuration editing, and visibility of another member's work separately. Edit access is not a success criterion when only use access was intended.

An Endpoint-key call validates the published interface's authentication and contract. It does not replace Namespace checks for ordinary users. Share the report or authorized work records, not an administrator account or internal token.
