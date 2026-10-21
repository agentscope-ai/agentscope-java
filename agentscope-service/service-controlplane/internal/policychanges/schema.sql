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
