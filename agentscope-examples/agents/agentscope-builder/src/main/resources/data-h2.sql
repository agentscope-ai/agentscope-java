-- ---------------------------------------------------------------------------
-- H2-only seed data (runs when spring.sql.init.platform=h2 and the JDBC URL
-- points at the embedded H2 driver).
--
-- Provides two demo accounts so a fresh local checkout can be exercised
-- end-to-end without first calling the admin API to create users:
--
--   bob   / bob    (role: user)
--   alice / alice  (role: user)
--
-- The password column stores BCrypt hashes ( cost = 10 ). These are
-- precomputed and committed verbatim — BCrypt embeds its own random salt in
-- the hash, so the same constant is verified successfully by Spring's
-- BCryptPasswordEncoder on every JVM. Regenerate with:
--
--   htpasswd -bnBC 10 "" <password> | tr -d '\n' | sed 's/^://'
--
-- `MERGE INTO ... KEY (user_id)` makes the seed idempotent — running this
-- script after the rows already exist updates them in place rather than
-- failing on the PK constraint, so application.yml safely runs it on every
-- startup (`spring.sql.init.mode=always`).
--
-- These accounts are scoped to H2 by `spring.sql.init.platform=h2`. The
-- `jdbc` Spring profile (application-jdbc.yml) flips
-- `spring.sql.init.mode=never`, so MySQL / PostgreSQL deployments never run
-- this script. (Note: in Spring Boot 4 the `embedded` mode only matches
-- `jdbc:h2:mem:` URLs, not file-mode H2 — that's why we default to
-- `always` instead.)
-- ---------------------------------------------------------------------------

MERGE INTO builder_user (user_id, username, password_hash, roles_csv, created_at) KEY (user_id) VALUES
  ('bob',   'bob',   '$2y$10$jlcRmUapzgu.P95uby8yx.1KSxQe.7Hmj0.fDJlg4OAOgfMruaU2y', 'user', CURRENT_TIMESTAMP),
  ('alice', 'alice', '$2y$10$KO33XwJbFTsFGTPIlrvLe.xvI0tK3PNKVNVGpHNQyMZhzDxVdoKA6', 'user', CURRENT_TIMESTAMP);
