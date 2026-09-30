# GitHub account connection

GitHub uses an administrator-managed OAuth application. Users connect their own
GitHub accounts from Agent → Definition → Tools & MCP without entering OAuth
endpoints, client IDs, client secrets, personal access tokens, or HTTP headers.
This feature authorizes GitHub tools; it does not add GitHub login to the platform.

## Administrator setup

1. Build and restart the Go control plane (`aistiod`) and build the frontend.
   The OAuth migration adds `oauth_provider_apps` and the provider/account metadata
   columns on existing OAuth connections. No Java runtime changes are required.
2. For a deployed console, configure `BUILDER_OAUTH_PUBLIC_URL` with its public
   HTTPS origin and use the existing stable Vault encryption key. Local loopback
   development supports HTTP. The browser must return to the same console host
   used when starting authorization; do not mix `localhost` and `127.0.0.1`.
3. Open **Settings → Integrations** (`/settings/integrations`) as a platform admin.
   The GitHub card shows the callback URL even before application registration.
   The fixed callback is `/api/oauth/mcp/callback/github`; on the local console it
   is `http://127.0.0.1:18080/api/oauth/mcp/callback/github`.
4. Register an OAuth App or a GitHub App with user authorization in GitHub developer
   settings. Configure that exact callback, then enter the application's **Client ID**
   and **client secret** on the platform. A GitHub App numeric App ID or private key
   is not a substitute for these values.
5. For an OAuth App, configure only the required scopes. `read:user` permits account
   profile access but does not grant access to private repositories. `repo` grants
   private repository access and also includes write privileges. For GitHub Apps,
   configure permissions and repository installation access on GitHub and leave
   the OAuth scopes field empty. Organization policy can require approval.
6. Enable and save. Application secrets are encrypted and never returned to the
   browser. Empty secret input preserves the saved secret when Client ID is unchanged.
   Concurrent edits use a revision check. Changing settings invalidates pending
   authorizations; changing Client ID requires a new secret and reauthorization.

## User flow

1. Add an MCP connection with endpoint `https://api.githubcopilot.com/mcp/`
   (with or without the trailing slash), then choose **Connect GitHub**.
2. Select or create a Vault. A Vault in a shared namespace shares account access
   with authorized users/Agents using that Vault. Use a personal namespace/Vault
   for private account access. Different users should use different Vaults when
   they need independent accounts for the same endpoint.
3. Click **Connect GitHub account**. GitHub opens in a new window for login and
   consent. No application settings are requested from the user.
4. The callback exchanges the authorization code with PKCE. The originating user
   completes the flow in the console; namespace/Vault permissions are rechecked.
   Tokens are encrypted in Vault and the Vault is added to the Agent defaults.
5. The console reads `/user` from GitHub and performs MCP `initialize`, initialized
   notification and `tools/list`, including pagination. It never invokes repository
   tools. The UI displays the GitHub login, granted scopes when provided by GitHub,
   verification time, and discovered tool count.
6. Start a **new Session** to use the Agent's updated Vault defaults. Existing
   sessions keep their configuration snapshot. Tool enablement and permission
   policies configured on the Agent still apply.

Existing accounts in a selected Vault can be reused with **Use for this agent**.
If attaching the Vault fails, **Retry adding Vault to agent** retries without
repeating consent. **Verify connection** repeats the read-only probe;
**Reconnect account** obtains a new grant; **Disconnect from Vault** removes the
local credential. Reconnection retains the previous credential until successful.

## States and limits

- Credential saved, account identity verified, and MCP connectivity verified are
  separate outcomes. A GitHub `403`, missing tools, or protocol failure does not
  turn an account grant into a successful tool connection. Diagnostics contain
  stable error codes, never remote response bodies or token values.
- The probe runs from the Go control plane. Java Brain may have different network
  reachability; successful probing does not override Agent tool policies or prove
  that an already-running session loaded the tools.
- Existing automatic token refresh remains available when GitHub supplies refresh
  tokens and expiry. Both initial exchange and refresh request JSON explicitly.
  Reauthorize after client credentials change or the provider revokes/expires a
  grant; there is no mid-turn Java MCP token hot replacement.
- Disabling the provider blocks new authorizations. It does not revoke issued
  grants. Disconnect is local deletion; revoke on GitHub for provider-side revocation.
- GitHub Enterprise endpoints and installation-token service identities are outside
  this first implementation. The managed provider accepts only the official remote
  GitHub MCP endpoint; administrator secrets cannot be retargeted by namespace users.
- Other MCP providers retain the existing custom OAuth form. Existing manually
  configured GitHub OAuth connections also retain their custom configuration; use
  a separate Vault for a new administrator-managed GitHub account connection.

## Validation

Backend tests run against a separate PostgreSQL database with simulated provider
responses: application admin permissions, revision checks, fixed callback, PKCE,
duplicate/cross-user callback completion, endpoint restrictions, identity/tool
verification, SSE parsing, failure redaction, and disconnect. Browser tests cover
administrator setup, successful connection, missing setup, MCP denial, and failed
Agent attachment followed by retry. These tests do not constitute live GitHub
authorization; a deployment's registered application must be tested with its user.

References: [GitHub MCP host integration](https://github.com/github/github-mcp-server/blob/main/docs/host-integration.md),
[GitHub OAuth authorization](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps).
