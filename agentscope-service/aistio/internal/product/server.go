// Copyright 2024-2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package product

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// Server is the product control plane module. It owns the `cp` schema and
// the console/gateway facing `/api/*` routes, but not an HTTP listener:
// aistiod mounts it onto the shared REST server so the whole control plane
// is one process on one port.
// TeamContextLookup returns opaque TeamContext JSON for a managed session id.
// Wired from the runtime store so /api/internal/sessions/{id}/resolve can
// project team membership without coupling product schema to Team rows.
type TeamContextLookup func(ctx context.Context, sessionID string) json.RawMessage

// ManagedExecutionContextLookup returns private task execution context for a
// managed session. It is exposed only through the internal resolve endpoint.
type ManagedExecutionContextLookup func(ctx context.Context, sessionID string) json.RawMessage

var (
	// ErrManagedRuntimeFenceConflict means a runtime status patch is malformed or
	// does not match the immutable fields of the named current Attempt.
	ErrManagedRuntimeFenceConflict = errors.New("managed runtime fence mismatch")
	// ErrManagedRuntimeFenceGone means the physical Attempt was failed,
	// cancelled, requeued, or replaced and its data-plane turn must stop.
	ErrManagedRuntimeFenceGone = errors.New("managed runtime Attempt is gone")
)

// ManagedRuntimeFence is captured when one physical managed turn is admitted.
// Every status patch from that turn carries this exact tuple; callers must not
// look up and adopt a newer session scope in a finally block.
type ManagedRuntimeFence struct {
	AgentTaskID        string `json:"agentTaskId,omitempty"`
	AttemptID          string `json:"attemptId,omitempty"`
	DispatchGeneration int64  `json:"dispatchGeneration,omitempty"`
	TurnID             string `json:"turnId,omitempty"`
}

func (f ManagedRuntimeFence) Complete() bool {
	return strings.TrimSpace(f.AgentTaskID) != "" && strings.TrimSpace(f.AttemptID) != "" &&
		f.DispatchGeneration > 0 && strings.TrimSpace(f.TurnID) != ""
}

func (f ManagedRuntimeFence) Empty() bool {
	return strings.TrimSpace(f.AgentTaskID) == "" && strings.TrimSpace(f.AttemptID) == "" &&
		f.DispatchGeneration == 0 && strings.TrimSpace(f.TurnID) == ""
}

// ManagedRuntimeFenceValidator reports whether the product session is bound
// to an AgentTask and verifies its current runtime-store fence. Personal chat
// sessions return managed=false and continue to accept an empty fence.
type ManagedRuntimeFenceValidator func(ctx context.Context, sessionID string,
	fence ManagedRuntimeFence) (managed bool, err error)

// TeamMemberActivityHook is invoked after a product session runtime status
// patch succeeds. Used to mirror idle/running onto store-backed team members.
type TeamMemberActivityHook func(ctx context.Context, sessionID, status string)

type Server struct {
	accountDisableGuard     func(context.Context, string) error
	oauthHTTPClient         *http.Client // Optional transport override for in-process provider tests.
	channelWork             *ChannelWorkRuntime
	cfg                     Config
	db                      *DB
	vaultKey                []byte
	teamContextLookup       TeamContextLookup
	executionContextLookup  ManagedExecutionContextLookup
	managedRuntimeValidator ManagedRuntimeFenceValidator
	teamMemberActivityHook  TeamMemberActivityHook
}

// SetTeamContextLookup injects the runtime-store TeamContext lookup used by resolve.
func (s *Server) SetTeamContextLookup(fn TeamContextLookup) {
	if s != nil {
		s.teamContextLookup = fn
	}
}

// SetManagedExecutionContextLookup injects the runtime-store lookup used to
// materialize task-scoped MCP credentials in the data plane.
func (s *Server) SetManagedExecutionContextLookup(fn ManagedExecutionContextLookup) {
	if s != nil {
		s.executionContextLookup = fn
	}
}

// SetManagedRuntimeFenceValidator injects the runtime-store authority used to
// fence product-session status projection from delayed physical turns.
func (s *Server) SetManagedRuntimeFenceValidator(fn ManagedRuntimeFenceValidator) {
	if s != nil {
		s.managedRuntimeValidator = fn
	}
}

// ValidateManagedRuntime verifies that a managed Agent can create an
// executable session, including the otherwise easy-to-miss Environment.
func (s *Server) ValidateManagedRuntime(ctx context.Context, ownerID, agentID string) error {
	if s == nil {
		return fmt.Errorf("managed control plane is unavailable")
	}
	if strings.TrimSpace(s.cfg.DataURL) == "" {
		return fmt.Errorf("managed data plane URL is not configured")
	}
	if _, err := s.loadAgent(ctx, ownerID, agentID); err != nil {
		return fmt.Errorf("managed Agent definition is unavailable")
	}
	if _, err := s.resolveDefaultEnvironmentID(ctx, ownerID, agentID); err != nil {
		return err
	}
	return nil
}

// SetTeamMemberActivityHook injects the store-backed member phase sync used when
// Managed turns update session status via PATCH .../runtime.
func (s *Server) SetTeamMemberActivityHook(fn TeamMemberActivityHook) {
	if s != nil {
		s.teamMemberActivityHook = fn
	}
}

// Open connects to Postgres, migrates the `cp` schema, and seeds default
// users. The caller owns the returned Server and must Close it.
func Open(ctx context.Context, cfg Config) (*Server, error) {
	if len(cfg.JWTSecret) < 32 {
		return nil, fmt.Errorf("jwt secret must be at least 32 characters")
	}
	if err := validateBootstrap(cfg); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(cfg.WorkspaceRoot, 0o755); err != nil {
		return nil, fmt.Errorf("workspace root: %w", err)
	}

	db, err := openDB(ctx, cfg.DSN)
	if err != nil {
		return nil, err
	}
	if err := migrate(ctx, db); err != nil {
		db.Close()
		return nil, err
	}
	if cfg.BootstrapAdmin != "" {
		if err := bootstrapAdmin(ctx, db, cfg); err != nil {
			db.Close()
			return nil, fmt.Errorf("bootstrap admin: %w", err)
		}
	} else if cfg.SeedUsers {
		if err := seedUsers(ctx, db); err != nil {
			db.Close()
			return nil, fmt.Errorf("seed users: %w", err)
		}
	}

	return &Server{
		cfg:      cfg,
		db:       db,
		vaultKey: vaultKey(cfg.VaultMasterKey, cfg.JWTSecret),
	}, nil
}

// Middlewares returns the auth chain that must wrap the product routes.
// They are scoped to the mount group so that sibling APIs (for example the
// Kubernetes-native /api/v1 surface) keep their own auth.
func (s *Server) Middlewares() []gin.HandlerFunc {
	return []gin.HandlerFunc{s.jwtMiddleware(), s.internalMiddleware()}
}

// Register mounts every product route onto the given router.
func (s *Server) Register(r gin.IRouter) {
	s.registerAuth(r)
	s.registerAgentExtras(r)
	s.registerWorkspace(r)
	s.registerWorkspaces(r)
	s.registerMarketplaces(r)
	s.registerChannels(r)
	s.registerAdmin(r)
	s.registerEnvironments(r)
	s.registerSessions(r)
	s.registerMemory(r)
	s.registerVaults(r)
	s.registerOAuth(r)
	s.registerDeployments(r)
	s.registerFiles(r)
	s.registerInternal(r)
}

// VerifyToken validates a console JWT, letting the shared REST server accept
// the same credential on the Kubernetes-native API.
func (s *Server) VerifyToken(token string) (*Claims, error) {
	return parseToken(s.cfg.JWTSecret, token)
}

// VerifyAccountToken also checks the live account, so deletion and platform-role
// revocation take effect without waiting for a seven-day JWT to expire.
func (s *Server) VerifyAccountToken(ctx context.Context, token string) (*Claims, error) {
	claims, err := s.VerifyToken(token)
	if err != nil {
		return nil, err
	}
	if s.db == nil {
		return nil, fmt.Errorf("account store unavailable")
	}
	var username, roles string
	var disabled bool
	var authVersion int64
	err = s.db.Pool.QueryRow(ctx, `SELECT username,roles_csv,disabled,auth_version FROM users WHERE user_id=$1`, claims.Subject).Scan(&username, &roles, &disabled, &authVersion)
	if err != nil || disabled || claims.AccountVersion != authVersion || claims.ExpiresAt == nil || claims.IssuedAt == nil {
		return nil, fmt.Errorf("account unavailable")
	}
	id := sessionFingerprint(token)
	_, err = s.db.Pool.Exec(ctx, `INSERT INTO account_login_sessions(id,user_id,created_at,expires_at) SELECT $1,$2,$3,$4 FROM users WHERE user_id=$2 AND legacy_sessions_closed=false AND disabled=false AND auth_version=$5 ON CONFLICT DO NOTHING`, id, claims.Subject, claims.IssuedAt.Time, claims.ExpiresAt.Time, claims.AccountVersion)
	if err != nil {
		return nil, err
	}
	var revoked bool
	if err = s.db.Pool.QueryRow(ctx, `SELECT revoked_at IS NOT NULL FROM account_login_sessions WHERE id=$1 AND user_id=$2`, id, claims.Subject).Scan(&revoked); err != nil || revoked {
		return nil, fmt.Errorf("login session revoked")
	}
	if _, err = s.db.Pool.Exec(ctx, `UPDATE account_login_sessions SET last_seen_at=now() WHERE id=$1 AND last_seen_at<now()-interval '1 minute'`, id); err != nil {
		return nil, err
	}
	claims.Username = username
	claims.Roles = splitRoles(roles)
	return claims, nil
}

func (s *Server) SetAccountDisableGuard(fn func(context.Context, string) error) {
	s.accountDisableGuard = fn
}

// Close releases the database pool.
func (s *Server) Close() {
	if s != nil {
		s.db.Close()
	}
}

func shortID(prefix string) string {
	u := uuid.New().String()
	u = strings.ReplaceAll(u, "-", "")
	if len(u) > 12 {
		u = u[:12]
	}
	return prefix + u
}

func mustJSON(v any) string {
	if v == nil {
		return "null"
	}
	b, err := json.Marshal(v)
	if err != nil {
		return "null"
	}
	return string(b)
}

func parseJSONRaw(s string) any {
	if s == "" || s == "null" {
		return nil
	}
	var v any
	if err := json.Unmarshal([]byte(s), &v); err != nil {
		return nil
	}
	return v
}

func parseStringSlice(s string) []string {
	if s == "" || s == "null" {
		return []string{}
	}
	var out []string
	if err := json.Unmarshal([]byte(s), &out); err != nil {
		return []string{}
	}
	if out == nil {
		return []string{}
	}
	return out
}

// pageParams parses optional offset pagination. A zero limit means "no limit",
// which preserves the historical full-dump behaviour.
func pageParams(c *gin.Context) (limit, offset int, ok bool) {
	limitStr := c.Query("limit")
	offsetStr := c.Query("offset")
	if limitStr == "" && offsetStr == "" {
		return 0, 0, true
	}
	if limitStr != "" {
		n, err := strconv.Atoi(limitStr)
		if err != nil || n < 1 || n > 500 {
			return 0, 0, false
		}
		limit = n
	}
	if offsetStr != "" {
		n, err := strconv.Atoi(offsetStr)
		if err != nil || n < 0 {
			return 0, 0, false
		}
		offset = n
	}
	if offsetStr != "" && limitStr == "" {
		// Offset without limit is ambiguous; require an explicit limit.
		return 0, 0, false
	}
	return limit, offset, true
}

func appendPage(q string, limit, offset int, args []any) (string, []any) {
	if limit <= 0 {
		return q, args
	}
	args = append(args, limit, offset)
	return q + fmt.Sprintf(" LIMIT $%d OFFSET $%d", len(args)-1, len(args)), args
}

func writeTotalCount(c *gin.Context, total int64) {
	c.Header("X-Total-Count", strconv.FormatInt(total, 10))
}

func nullMillis(v *int64) any {
	if v == nil || *v == 0 {
		return nil
	}
	return *v
}

func writeErr(c *gin.Context, status int, msg string) {
	c.JSON(status, gin.H{"error": msg})
}

func writeTextErr(c *gin.Context, status int, msg string) {
	c.String(status, msg)
}
