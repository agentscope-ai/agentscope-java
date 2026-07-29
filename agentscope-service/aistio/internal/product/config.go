package product

// Config holds runtime settings for the product control plane module.
// The module does not own an HTTP listener; aistiod mounts it onto the
// shared REST server.
type Config struct {
	DSN            string
	JWTSecret      string
	InternalToken  string
	WorkspaceRoot  string
	SeedUsers      bool
	DataURL        string // BUILDER_DATA_URL
	VaultMasterKey string // BUILDER_VAULT_MASTER_KEY (optional)
}

// DefaultConfig returns development defaults.
func DefaultConfig() Config {
	return Config{
		DSN:           "postgres://builder:builder@localhost:5432/builder?sslmode=disable",
		JWTSecret:     "builder-default-dev-secret-change-in-production-32chars",
		InternalToken: "builder-internal-dev-token",
		WorkspaceRoot: "./data/workspaces",
		SeedUsers:     true,
	}
}
