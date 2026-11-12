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
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/policychanges"
)

const policyChangeTriggers = `
DROP TRIGGER IF EXISTS account_policy_change ON users;
CREATE TRIGGER account_policy_change AFTER UPDATE OF disabled,roles_csv,auth_version OR DELETE ON users
 FOR EACH ROW EXECUTE FUNCTION emit_policy_change('global');
DROP TRIGGER IF EXISTS share_policy_change ON resource_shares;
CREATE TRIGGER share_policy_change AFTER INSERT OR UPDATE OR DELETE ON resource_shares
 FOR EACH ROW EXECUTE FUNCTION emit_policy_change('global');
DROP TRIGGER IF EXISTS agent_policy_change ON agents;
CREATE TRIGGER agent_policy_change AFTER UPDATE OF archived_at OR DELETE ON agents
 FOR EACH ROW EXECUTE FUNCTION emit_policy_change('global');
DROP TRIGGER IF EXISTS environment_policy_change ON environments;
CREATE TRIGGER environment_policy_change AFTER UPDATE OF archived_at,config_json,api_key_hash OR DELETE ON environments
 FOR EACH ROW EXECUTE FUNCTION emit_policy_change('global');
DROP TRIGGER IF EXISTS memory_policy_change ON memory_stores;
CREATE TRIGGER memory_policy_change AFTER UPDATE OF archived_at OR DELETE ON memory_stores
 FOR EACH ROW EXECUTE FUNCTION emit_policy_change('global');
DROP TRIGGER IF EXISTS vault_policy_change ON vaults;
CREATE TRIGGER vault_policy_change AFTER UPDATE OF archived_at OR DELETE ON vaults
 FOR EACH ROW EXECUTE FUNCTION emit_policy_change('global');
DROP TRIGGER IF EXISTS vault_credential_policy_change ON vault_credentials;
CREATE TRIGGER vault_credential_policy_change AFTER INSERT OR UPDATE OR DELETE ON vault_credentials
 FOR EACH ROW EXECUTE FUNCTION emit_policy_change('global');

`

func (s *Server) PolicyChanges() policychanges.Repository {
	return &policychanges.Postgres{Pool: s.db.Pool}
}
