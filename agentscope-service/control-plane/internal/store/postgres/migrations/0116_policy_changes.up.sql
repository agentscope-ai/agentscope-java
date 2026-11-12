-- Changes commit atomically with the originating resource/permission mutation.
CREATE TABLE IF NOT EXISTS policy_change_outbox (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant TEXT NOT NULL DEFAULT '', namespace TEXT NOT NULL DEFAULT '',
 application_id TEXT NOT NULL DEFAULT '',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 claim_token TEXT, claim_until TIMESTAMPTZ, delivered_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS policy_change_pending ON policy_change_outbox(created_at,id)
 WHERE delivered_at IS NULL;
CREATE OR REPLACE FUNCTION emit_policy_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE value JSONB; t TEXT := ''; n TEXT := ''; a TEXT := '';
BEGIN
 value := CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
 IF TG_ARGV[0]='namespace' THEN t:=value->>'tenant'; n:=value->>'name';
 ELSIF TG_ARGV[0]='resource' THEN t:=value->>'tenant'; n:=value->>'namespace';
 ELSIF TG_ARGV[0]='application' THEN t:=value->>'tenant'; n:=value->>'namespace'; a:=value->>'id';
 ELSIF TG_ARGV[0]='credential' THEN
   IF value->>'ns_path' NOT LIKE 'application-credentials/%' THEN RETURN NULL; END IF;
   t:=value->>'tenant'; a:=substring(value->>'ns_path' from 25);
 END IF;
 -- Resolve the outbox in the source table's schema, including the product cp schema.
 EXECUTE format('INSERT INTO %I.policy_change_outbox(tenant,namespace,application_id) VALUES($1,$2,$3)',TG_TABLE_SCHEMA)
 USING coalesce(t,''),coalesce(n,''),coalesce(a,'');
 RETURN NULL;
END $$;

CREATE TRIGGER namespace_policy_change AFTER INSERT OR UPDATE OR DELETE ON access_namespaces
 FOR EACH ROW EXECUTE FUNCTION emit_policy_change('namespace');
CREATE TRIGGER application_policy_change AFTER UPDATE OF members,status,max_concurrent,token_budget ON applications
 FOR EACH ROW WHEN (OLD IS DISTINCT FROM NEW) EXECUTE FUNCTION emit_policy_change('application');
CREATE TRIGGER credential_policy_change AFTER INSERT OR UPDATE OR DELETE ON dp_kv
 FOR EACH ROW EXECUTE FUNCTION emit_policy_change('credential');

CREATE TRIGGER catalog_agent_policy_change AFTER UPDATE OF status,archived_at OR DELETE ON agents
 FOR EACH ROW EXECUTE FUNCTION emit_policy_change('resource');
CREATE TRIGGER catalog_binding_policy_change AFTER UPDATE OF enabled,archived_at OR DELETE ON agent_bindings
 FOR EACH ROW EXECUTE FUNCTION emit_policy_change('resource');
