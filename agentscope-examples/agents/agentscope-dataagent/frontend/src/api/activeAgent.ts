/**
 * The single data agent the user-facing UI is bound to.
 *
 * The DataAgent product is conceptually one agent — multi-agent management is a backend
 * capability, not a primary user surface. All chat/session/workspace/configure routes use
 * this id when calling per-agent APIs.
 */
export const ACTIVE_AGENT_ID = 'data-agent';
