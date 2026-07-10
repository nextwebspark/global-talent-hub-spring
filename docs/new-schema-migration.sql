-- new-schema-migration.sql -- one-time migration from the live schema in docs/05_app_portal.sql
-- (app_projects.client_company_id -> app_companies) to the app_clients design proposed in
-- new-schema.sql (app_projects.client_id -> app_clients -> optionally app_companies).
--
-- Idempotent and safe to re-run at any point, including:
--   - before anything has changed (creates app_clients, backfills, attempts cutover)
--   - after a partial run that couldn't fully cut over (picks up where it left off)
--   - after the cutover already completed (detects client_company_id is already gone and
--     does nothing further)
--
-- The only step that can legitimately not complete in one pass is the final cutover (dropping
-- client_company_id + making client_id NOT NULL): if some existing app_projects rows have a
-- NULL client_company_id (the earlier docs/05_app_portal.sql migration would have left such
-- rows in place with a NOTICE rather than fail), there's no data to backfill an app_clients row
-- from, so client_id would stay NULL for those rows too. Rather than guess, this migration
-- leaves client_company_id in place alongside the new client_id column and reports exactly how
-- many rows are blocking the cutover. Assign those manually (see the note at the bottom), then
-- re-run this file to finish.

BEGIN;

-- ============================================================================
-- 1. app_clients -- same definition as new-schema.sql.
-- ============================================================================
CREATE TABLE IF NOT EXISTS app_clients (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id             UUID NOT NULL,
    created_by         UUID,
    name               TEXT NOT NULL,
    linked_company_id  BIGINT REFERENCES app_companies(id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_clients_org_linked_company UNIQUE (org_id, linked_company_id)
);
CREATE INDEX IF NOT EXISTS idx_clients_org            ON app_clients (org_id);
CREATE INDEX IF NOT EXISTS idx_clients_linked_company  ON app_clients (linked_company_id);

-- ============================================================================
-- 2. app_projects.client_id -- new nullable column, populated below, tightened to NOT NULL
-- only once every row has one (see the guarded cutover at the bottom).
-- ============================================================================
ALTER TABLE app_projects ADD COLUMN IF NOT EXISTS client_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_projects_client') THEN
        ALTER TABLE app_projects
            ADD CONSTRAINT fk_projects_client
            FOREIGN KEY (client_id) REFERENCES app_clients(id) ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_projects_client ON app_projects (client_id);

-- ============================================================================
-- 3. Backfill app_clients from existing client_company_id values, populate client_id, and
-- attempt the cutover -- all gated on client_company_id still existing, so re-running this
-- file after a completed cutover is a clean no-op.
-- ============================================================================
DO $$
DECLARE
    unresolved INTEGER;
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'app_projects' AND column_name = 'client_company_id'
    ) THEN
        -- One app_clients row per distinct (org, catalog company) pair actually referenced by
        -- an existing project; name is snapshotted from app_companies at backfill time.
        INSERT INTO app_clients (org_id, name, linked_company_id)
        SELECT DISTINCT p.org_id, c.name, p.client_company_id
        FROM app_projects p
        JOIN app_companies c ON c.id = p.client_company_id
        WHERE p.client_company_id IS NOT NULL
        ON CONFLICT (org_id, linked_company_id) DO NOTHING;

        -- Point each project at the app_clients row that now represents its old
        -- client_company_id within the same org.
        UPDATE app_projects p
        SET client_id = ac.id
        FROM app_clients ac
        WHERE ac.org_id = p.org_id
          AND ac.linked_company_id = p.client_company_id
          AND p.client_id IS NULL
          AND p.client_company_id IS NOT NULL;

        SELECT count(*) INTO unresolved FROM app_projects WHERE client_id IS NULL;
        IF unresolved = 0 THEN
            ALTER TABLE app_projects ALTER COLUMN client_id SET NOT NULL;
            ALTER TABLE app_projects DROP COLUMN client_company_id;
            RAISE NOTICE 'Cutover complete: app_projects.client_company_id dropped, client_id is now NOT NULL.';
        ELSE
            RAISE NOTICE 'Cutover NOT completed: % row(s) have no client_company_id to backfill from (were already NULL). client_company_id left in place alongside client_id -- assign app_clients rows and UPDATE app_projects.client_id for those rows manually, then re-run this file to finish.', unresolved;
        END IF;
    ELSE
        RAISE NOTICE 'Migration already complete: app_projects.client_company_id no longer exists; nothing to backfill.';
    END IF;
END $$;

COMMIT;

-- ----------------------------------------------------------------------------
-- If the NOTICE above reported unresolved rows, find and fix them with something like:
--
--   SELECT id, name FROM app_projects WHERE client_id IS NULL;
--   -- for each: either create/find the right app_clients row and:
--   UPDATE app_projects SET client_id = <app_clients.id> WHERE id = <project id>;
--   -- then re-run this file -- the cutover (NOT NULL + drop client_company_id) will complete.
-- ----------------------------------------------------------------------------

-- ----------------------------------------------------------------------------
-- Add optional web domain to app_clients (used to auto-match a catalog company
-- by app_companies.domain at client-create time). Idempotent.
-- ----------------------------------------------------------------------------
ALTER TABLE app_clients ADD COLUMN IF NOT EXISTS domain TEXT;
