-- app_portal: writable, org-scoped tables for the v2 search -> universe -> project flow
-- (docs/v2/phase-00 through phase-04). CANONICAL schema (adopts the app_clients design).
--
-- Client is modeled as its own org-owned table `app_clients` (name always present, with an
-- OPTIONAL link to a catalog company for display), NOT a direct app_companies FK -- so a real
-- client the vendor scrape never captured can still be used. See new-schema.sql for the
-- rationale and new-schema-migration.sql for upgrading a DB that still has the older
-- app_projects.client_company_id column.
--
-- Idempotent and SAFE TO RE-RUN against a fresh DB (the CREATE TABLE IF NOT EXISTS blocks
-- carry the final, tightened definition) or a DB already on this shape. Tightening steps that
-- could fail on real data are guarded (check + NOTICE, never abort). NOTE: upgrading a DB that
-- still has the OLD client_company_id column is done by new-schema-migration.sql, not here.
--
-- All FKs to app_companies.id rely on that table's id being a STABLE surrogate key across
-- reloads -- see the warning at the top of docs/04_app_companies.sql.

BEGIN;

-- ============================================================================
-- app_search_runs -- one search execution: raw query + LLM-parsed criteria
-- (phase 02) + result count + status.
-- ============================================================================

CREATE TABLE IF NOT EXISTS app_search_runs (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id           UUID NOT NULL,
    created_by       UUID,
    query            TEXT NOT NULL,
    mode             TEXT NOT NULL DEFAULT 'Search',   -- free string: 'Search' | 'Import a list' | 'From brief'
    parsed_criteria  JSONB,                            -- {industry[],country[],revenueRange[],employeeRange[], positions[],seniority[],experience[]}
    result_count     INTEGER,
    status           TEXT NOT NULL DEFAULT 'active'
                     CONSTRAINT chk_search_runs_status CHECK (status IN ('active', 'archived')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Existing DB: mode was nullable with no default -- backfill then tighten.
UPDATE app_search_runs SET mode = 'Search' WHERE mode IS NULL;
ALTER TABLE app_search_runs ALTER COLUMN mode SET DEFAULT 'Search';
ALTER TABLE app_search_runs ALTER COLUMN mode SET NOT NULL;

-- Existing DB: add the status CHECK if it isn't already there, but only if every existing
-- row already fits the set -- otherwise skip and say why, rather than abort the migration.
DO $$
DECLARE
    bad_count INTEGER;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_search_runs_status') THEN
        SELECT count(*) INTO bad_count FROM app_search_runs WHERE status NOT IN ('active', 'archived');
        IF bad_count = 0 THEN
            ALTER TABLE app_search_runs
                ADD CONSTRAINT chk_search_runs_status CHECK (status IN ('active', 'archived'));
        ELSE
            RAISE NOTICE 'Skipped chk_search_runs_status: % row(s) have a status outside active/archived. Fix the data, then re-run this file.', bad_count;
        END IF;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_search_runs_org          ON app_search_runs (org_id);
CREATE INDEX IF NOT EXISTS idx_search_runs_org_created  ON app_search_runs (org_id, created_at DESC);

-- ============================================================================
-- app_clients -- the org's own client relationships (who a project is FOR). Always has a
-- name (works even when the client isn't in the vendor catalog); linked_company_id is an
-- OPTIONAL enrichment pointer to app_companies for display (industry, logo, HQ, ...) when the
-- client happens to also be a catalog row.
-- ============================================================================

CREATE TABLE IF NOT EXISTS app_clients (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id             UUID NOT NULL,
    created_by         UUID,
    name               TEXT NOT NULL,
    -- optional web domain (e.g. acme.com), used to auto-match a catalog company by app_companies.domain
    domain             TEXT,
    linked_company_id  BIGINT REFERENCES app_companies(id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- prevents an org creating two client rows for the same catalog company; multiple NULLs
    -- (unlinked clients) are unaffected -- Postgres treats each NULL as distinct.
    CONSTRAINT uq_clients_org_linked_company UNIQUE (org_id, linked_company_id)
);
CREATE INDEX IF NOT EXISTS idx_clients_org             ON app_clients (org_id);
CREATE INDEX IF NOT EXISTS idx_clients_linked_company   ON app_clients (linked_company_id);

-- ============================================================================
-- app_projects -- the "search map" workspace: belongs to a CLIENT (app_clients, this org's
-- own client record, which may or may not resolve to a catalog company), originates from a
-- search run.
-- ============================================================================

CREATE TABLE IF NOT EXISTS app_projects (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id         UUID NOT NULL,
    created_by     UUID,
    name           TEXT NOT NULL,
    client_id      BIGINT NOT NULL,
    search_run_id  BIGINT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'active'
                   CONSTRAINT chk_projects_status CHECK (status IN ('active', 'archived')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- FKs (idempotent add -- Postgres has no `ADD CONSTRAINT IF NOT EXISTS`).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_projects_client') THEN
        ALTER TABLE app_projects
            ADD CONSTRAINT fk_projects_client
            FOREIGN KEY (client_id) REFERENCES app_clients(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_projects_search_run') THEN
        ALTER TABLE app_projects
            ADD CONSTRAINT fk_projects_search_run
            FOREIGN KEY (search_run_id) REFERENCES app_search_runs(id) ON DELETE RESTRICT;
    END IF;
END $$;

-- Existing DB (already on client_id): tighten NOT NULL only if every row has both set.
DO $$
DECLARE
    null_count INTEGER;
BEGIN
    SELECT count(*) INTO null_count FROM app_projects WHERE client_id IS NULL;
    IF null_count = 0 THEN
        ALTER TABLE app_projects ALTER COLUMN client_id SET NOT NULL;
    ELSE
        RAISE NOTICE 'Skipped NOT NULL on app_projects.client_id: % row(s) have no client. See new-schema-migration.sql / backfill, then re-run.', null_count;
    END IF;

    SELECT count(*) INTO null_count FROM app_projects WHERE search_run_id IS NULL;
    IF null_count = 0 THEN
        ALTER TABLE app_projects ALTER COLUMN search_run_id SET NOT NULL;
    ELSE
        RAISE NOTICE 'Skipped NOT NULL on app_projects.search_run_id: % row(s) have no search run. Backfill or remove them, then re-run this file.', null_count;
    END IF;
END $$;

DO $$
DECLARE
    bad_count INTEGER;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_projects_status') THEN
        SELECT count(*) INTO bad_count FROM app_projects WHERE status NOT IN ('active', 'archived');
        IF bad_count = 0 THEN
            ALTER TABLE app_projects ADD CONSTRAINT chk_projects_status CHECK (status IN ('active', 'archived'));
        ELSE
            RAISE NOTICE 'Skipped chk_projects_status: % row(s) have a status outside active/archived. Fix the data, then re-run this file.', bad_count;
        END IF;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_projects_org          ON app_projects (org_id);
CREATE INDEX IF NOT EXISTS idx_projects_org_updated  ON app_projects (org_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_projects_client       ON app_projects (client_id);
CREATE INDEX IF NOT EXISTS idx_projects_search_run    ON app_projects (search_run_id);

-- ============================================================================
-- app_project_companies -- join of a project's universe to master companies +
-- per-company triage/mapping state. (Universe members genuinely ARE catalog rows,
-- so this still FKs company_id -> app_companies.)
-- ============================================================================

CREATE TABLE IF NOT EXISTS app_project_companies (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id          UUID NOT NULL,
    project_id      BIGINT NOT NULL,
    company_id      BIGINT NOT NULL,
    relevance_type  TEXT NOT NULL DEFAULT 'Direct'
                    CONSTRAINT chk_project_companies_relevance CHECK (relevance_type IN ('Direct', 'Adjacent', 'AI Inferred')),
    confidence      INTEGER
                    CONSTRAINT chk_project_companies_confidence CHECK (confidence BETWEEN 0 AND 100),
    status          VARCHAR NOT NULL DEFAULT 'untriaged'
                    CONSTRAINT chk_project_companies_status CHECK (status IN ('untriaged', 'in_universe', 'shortlisted', 'declined')),
    map_x           NUMERIC,
    map_y           NUMERIC,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_companies_project_company UNIQUE (project_id, company_id)
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_project_companies_project') THEN
        ALTER TABLE app_project_companies
            ADD CONSTRAINT fk_project_companies_project
            FOREIGN KEY (project_id) REFERENCES app_projects(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_project_companies_company') THEN
        ALTER TABLE app_project_companies
            ADD CONSTRAINT fk_project_companies_company
            FOREIGN KEY (company_id) REFERENCES app_companies(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_project_companies_project_company') THEN
        ALTER TABLE app_project_companies
            ADD CONSTRAINT uq_project_companies_project_company UNIQUE (project_id, company_id);
    END IF;
END $$;

-- Existing DB: relevance_type was nullable with no default -- backfill then tighten.
UPDATE app_project_companies SET relevance_type = 'Direct' WHERE relevance_type IS NULL;
ALTER TABLE app_project_companies ALTER COLUMN relevance_type SET DEFAULT 'Direct';
ALTER TABLE app_project_companies ALTER COLUMN relevance_type SET NOT NULL;

-- CHECKs on relevance_type / confidence / status: add only if every existing row already
-- fits, since relevance_type in particular was free text before this migration.
DO $$
DECLARE
    bad_count INTEGER;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_project_companies_relevance') THEN
        SELECT count(*) INTO bad_count FROM app_project_companies
            WHERE relevance_type NOT IN ('Direct', 'Adjacent', 'AI Inferred');
        IF bad_count = 0 THEN
            ALTER TABLE app_project_companies
                ADD CONSTRAINT chk_project_companies_relevance CHECK (relevance_type IN ('Direct', 'Adjacent', 'AI Inferred'));
        ELSE
            RAISE NOTICE 'Skipped chk_project_companies_relevance: % row(s) have an unrecognized relevance_type. Fix the data, then re-run this file.', bad_count;
        END IF;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_project_companies_confidence') THEN
        SELECT count(*) INTO bad_count FROM app_project_companies
            WHERE confidence IS NOT NULL AND confidence NOT BETWEEN 0 AND 100;
        IF bad_count = 0 THEN
            ALTER TABLE app_project_companies
                ADD CONSTRAINT chk_project_companies_confidence CHECK (confidence BETWEEN 0 AND 100);
        ELSE
            RAISE NOTICE 'Skipped chk_project_companies_confidence: % row(s) have a confidence outside 0-100. Fix the data, then re-run this file.', bad_count;
        END IF;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_project_companies_status') THEN
        SELECT count(*) INTO bad_count FROM app_project_companies
            WHERE status NOT IN ('untriaged', 'in_universe', 'shortlisted', 'declined');
        IF bad_count = 0 THEN
            ALTER TABLE app_project_companies
                ADD CONSTRAINT chk_project_companies_status CHECK (status IN ('untriaged', 'in_universe', 'shortlisted', 'declined'));
        ELSE
            RAISE NOTICE 'Skipped chk_project_companies_status: % row(s) have an unrecognized status. Fix the data, then re-run this file.', bad_count;
        END IF;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_proj_companies_project         ON app_project_companies (project_id);
CREATE INDEX IF NOT EXISTS idx_proj_companies_company         ON app_project_companies (company_id);
CREATE INDEX IF NOT EXISTS idx_proj_companies_project_status  ON app_project_companies (project_id, status);

COMMIT;
