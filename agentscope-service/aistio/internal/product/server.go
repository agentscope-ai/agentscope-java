package product

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// Server is the product control plane module. It owns the `cp` schema and
// the console/gateway facing `/api/*` routes, but not an HTTP listener:
// aistiod mounts it onto the shared REST server so the whole control plane
// is one process on one port.
type Server struct {
	cfg      Config
	db       *DB
	vaultKey []byte
}

// Open connects to Postgres, migrates the `cp` schema, and seeds default
// users. The caller owns the returned Server and must Close it.
func Open(ctx context.Context, cfg Config) (*Server, error) {
	if len(cfg.JWTSecret) < 32 {
		return nil, fmt.Errorf("jwt secret must be at least 32 characters")
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
	if cfg.SeedUsers {
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
	s.registerAgents(r)
	s.registerAgentExtras(r)
	s.registerWorkspace(r)
	s.registerChannels(r)
	s.registerAdmin(r)
	s.registerEnvironments(r)
	s.registerSessions(r)
	s.registerMemory(r)
	s.registerVaults(r)
	s.registerDeployments(r)
	s.registerInternal(r)
}

// VerifyToken validates a console JWT, letting the shared REST server accept
// the same credential on the Kubernetes-native API.
func (s *Server) VerifyToken(token string) (*Claims, error) {
	return parseToken(s.cfg.JWTSecret, token)
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
