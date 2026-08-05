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
