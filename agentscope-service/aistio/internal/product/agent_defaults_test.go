package product

import "testing"

func TestMergeSessionMounts(t *testing.T) {
	env := "env_default"
	vaultJSON := `["vault_a","vault_b"]`
	memJSON := `["mem_1"]`
	a := agentRow{
		DefaultEnvironmentID:       &env,
		DefaultVaultIDsJSON:        &vaultJSON,
		DefaultMemoryStoreIDsJSON:  &memJSON,
	}

	t.Run("all omitted uses agent defaults", func(t *testing.T) {
		gotEnv, mem, vault := mergeSessionMounts(a, "", nil, nil, false, false)
		if gotEnv != "env_default" {
			t.Fatalf("env=%q", gotEnv)
		}
		if len(mem) != 1 || mem[0] != "mem_1" {
			t.Fatalf("mem=%v", mem)
		}
		if len(vault) != 2 || vault[0] != "vault_a" {
			t.Fatalf("vault=%v", vault)
		}
	})

	t.Run("explicit env keeps caller", func(t *testing.T) {
		gotEnv, _, _ := mergeSessionMounts(a, "env_override", nil, nil, false, false)
		if gotEnv != "env_override" {
			t.Fatalf("env=%q", gotEnv)
		}
	})

	t.Run("explicit empty vault clears", func(t *testing.T) {
		_, _, vault := mergeSessionMounts(a, "env_x", nil, []string{}, false, true)
		if len(vault) != 0 {
			t.Fatalf("vault=%v", vault)
		}
	})

	t.Run("explicit memory overrides", func(t *testing.T) {
		_, mem, _ := mergeSessionMounts(a, "env_x", []string{"mem_other"}, nil, true, false)
		if len(mem) != 1 || mem[0] != "mem_other" {
			t.Fatalf("mem=%v", mem)
		}
	})
}
