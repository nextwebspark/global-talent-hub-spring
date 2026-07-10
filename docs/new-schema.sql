-- new-schema.sql -- PROPOSAL, not applied. An alternative to docs/05_app_portal.sql that fixes
-- one conceptual issue the user_search.sql walkthrough surfaced: "client" and "universe member"
-- were both modeled as `app_companies` rows, but they are not the same kind of thing.
--
--   app_companies   = shared, read-only, vendor-sourced catalog (BrightData GCC scrape).
--                     Correct FK target for "a company in the search universe" -- unchanged,
--                     see docs/04_app_companies.sql. Not redefined here.
--
--   "client"        = the org's own business relationship: who a project is being done FOR.
--                     That's org-owned data (like app_projects/app_search_runs), not a catalog
--                     row -- and today, requiring clientCompanyId to exist in app_companies means
--                     a real client the vendor scrape never picked up simply can't be used.
--
-- This file adds `app_clients` (org-scoped, name always present, optional link to a catalog row
-- for display enrichment when one happens to exist) and repoints app_projects at it instead of
-- directly at app_companies.
--
-- Everything else (app_search_runs, app_project_companies -> app_companies) is UNCHANGED from
-- docs/05_app_portal.sql and not repeated here except where the FK target changes.
--
-- NOTE: this is a greenfield definition (as if applied to an empty DB), not a live migration.
-- Adopting this against the already-live tables from docs/05_app_portal.sql needs a one-time
-- backfill (one app_clients row per distinct existing client_company_id, per org, then swap the
-- FK) -- ask for that migration script once/if this design is accepted.

BEGIN;

-- ============================================================================
-- app_clients -- the org's own client relationships. Always has a name (works even when
-- the client isn't in the vendor catalog); linked_company_id is an OPTIONAL enrichment
-- pointer to app_companies for display (industry, logo, HQ, etc.) when the client happens
-- to also be a catalog row.
-- ============================================================================
CREATE TABLE IF NOT EXISTS app_clients (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id             UUID NOT NULL,
    created_by         UUID,
    name               TEXT NOT NULL,
    linked_company_id  BIGINT REFERENCES app_companies(id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- prevents an org accidentally creating two client rows for the same catalog company;
    -- multiple NULLs (unlinked clients) are unaffected -- Postgres treats each NULL as distinct.
    CONSTRAINT uq_clients_org_linked_company UNIQUE (org_id, linked_company_id)
);
CREATE INDEX IF NOT EXISTS idx_clients_org             ON app_clients (org_id);
CREATE INDEX IF NOT EXISTS idx_clients_linked_company   ON app_clients (linked_company_id);

-- ============================================================================
-- app_projects -- same as docs/05_app_portal.sql, except client_company_id is replaced by
-- client_id -> app_clients(id). Renamed (not just retyped) so nothing reads it as "a company
-- row" by mistake -- it's now "this org's client record," which may or may not resolve to a
-- catalog company via app_clients.linked_company_id.
-- ============================================================================
CREATE TABLE IF NOT EXISTS app_projects (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id         UUID NOT NULL,
    created_by     UUID,
    name           TEXT NOT NULL,
    client_id      BIGINT NOT NULL REFERENCES app_clients(id) ON DELETE RESTRICT,
    search_run_id  BIGINT NOT NULL REFERENCES app_search_runs(id) ON DELETE RESTRICT,
    status         TEXT NOT NULL DEFAULT 'active'
                   CONSTRAINT chk_projects_status CHECK (status IN ('active', 'archived')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_projects_org          ON app_projects (org_id);
CREATE INDEX IF NOT EXISTS idx_projects_org_updated  ON app_projects (org_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_projects_client       ON app_projects (client_id);
CREATE INDEX IF NOT EXISTS idx_projects_search_run    ON app_projects (search_run_id);

-- app_project_companies is UNCHANGED from docs/05_app_portal.sql (still FKs company_id to
-- app_companies -- universe members genuinely are catalog rows). Not repeated here.

COMMIT;


-- ============================================================================
-- Re-walking user_search.sql's scenario against this schema
-- ============================================================================
-- Client picker now queries the ORG'S OWN clients first (fast, "have we worked with them
-- before"), falling back to the catalog only to link/create a new one:
--
--   SELECT id, name, linked_company_id FROM app_clients
--   WHERE org_id = '11111111-1111-1111-1111-111111111111' AND name ILIKE '%Al Rabie%';
--
--   -- not found yet -> create it, optionally linked to the catalog row for display:
--   INSERT INTO app_clients (org_id, created_by, name, linked_company_id)
--   SELECT '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222',
--          name, id
--   FROM app_companies WHERE source = 'user_search_demo' AND source_id = 'usd-104'
--   RETURNING id;
--   -- id = 9001
--
-- Project creation then references the client record, not the catalog row directly:
--   INSERT INTO app_projects (org_id, created_by, name, client_id, search_run_id, status)
--   VALUES ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222',
--           'Top FMCG distributors in UAE', 9001, 501, 'active');
--
-- If Al Rabie had never been scraped into app_companies at all, the exact same flow works --
-- just insert app_clients with linked_company_id left NULL. That case was previously
-- impossible: docs/05_app_portal.sql's client_company_id NOT NULL + FK to app_companies would
-- have rejected the project outright with no row to point at.
