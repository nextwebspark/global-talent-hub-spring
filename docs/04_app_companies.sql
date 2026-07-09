-- app_companies: flat, query-friendly recruitment master table.
-- Derived (materialized) from the `companies` spine + 6 columnar src_* tables.
-- Best-source-per-field, measured live on the 54,044-row corpus (see plan).
-- Rebuild: apply this file, then run loader/build_app_companies.py.
-- Idempotent: CREATE ... IF NOT EXISTS (safe to re-run against an existing DB).
--
-- IMPORTANT: this table's `id` is a stable surrogate key that docs/05_app_portal.sql
-- FKs to (app_projects.client_company_id, app_project_companies.company_id). Do NOT
-- drop/truncate/recreate this table on refresh -- that resets the IDENTITY sequence
-- and silently repoints every existing project/search-run at the wrong company.
-- The loader MUST refresh via UPSERT keyed on the (source, source_id) unique index
-- below (INSERT ... ON CONFLICT (source, source_id) DO UPDATE ...), never
-- DELETE+INSERT or DROP+CREATE.

BEGIN;

CREATE TABLE IF NOT EXISTS app_companies (
    -- own surrogate key (never tied to any vendor; portal FKs to this)
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- source provenance (a company may come from other providers later)
    source            TEXT NOT NULL DEFAULT 'brightdata',  -- provider name
    source_id         TEXT NOT NULL,                       -- provider's native id
    -- link back to the brightdata spine (= source_id when source='brightdata')
    company_id        TEXT REFERENCES companies(company_id) ON DELETE CASCADE,
    -- identity
    name              TEXT NOT NULL,
    slogan            TEXT,            -- lc.slogan tagline (~66%), also in search_text
    linkedin_url      TEXT,
    website           TEXT,
    domain            TEXT,
    logo              TEXT,
    -- classification (filterable) — industry is a first-class, multi-source concern
    primary_industry  TEXT,           -- single canonical label (lc -> ow -> zi)
    industry_tags     TEXT[],         -- 7-source merge, ~1,702 distinct, 99.99% cov
    sic_codes         TEXT[],         -- valid 4-digit SIC from Owler only (~6% cov)
    sic_labels        TEXT[],         -- sic_codes decoded to English (loader/sic_titles.py)
    specialties       TEXT[],         -- lc.specialties split on comma (also folded into tags)
    org_type          TEXT,
    ownership         TEXT,
    ipo_status        TEXT,
    is_public         BOOLEAN,        -- ipo_status = 'public'
    -- size / money (filterable)
    revenue_usd       BIGINT,
    revenue_range     TEXT,           -- derived band from revenue_usd
    revenue_source    TEXT,           -- 'owler' | 'zoominfo'
    revenue_is_floor  BOOLEAN,        -- zi 5,000,000 filter-threshold placeholder
    employee_count    INTEGER,
    employee_range    TEXT,           -- derived band from employee_count
    employee_source   TEXT,           -- 'linkedin' | 'zoominfo' | 'owler'
    -- geo (filterable)
    hq_country        TEXT,
    hq_city           TEXT,           -- best-effort: ow.city -> first token of lc.headquarters
    markets           TEXT[],         -- GCC states from spine
    -- context / signals
    description       TEXT,
    founded           INTEGER,
    followers         INTEGER,
    gd_rating         NUMERIC,
    gd_reviews        INTEGER,
    -- semantic-search staging (MEANING-ONLY text; no codes/numbers/urls)
    search_text       TEXT,           -- name+slogan+description+industry_tags+specialties+primary_industry+hq_country
    -- embedding VECTOR(N)  -- added in a later pgvector follow-up
    built_at          TIMESTAMPTZ DEFAULT now()
);

-- Provenance uniqueness: one row per (provider, provider's native id). Drives the
-- idempotent UPSERT rebuild and dedup when other sources are added later.
CREATE UNIQUE INDEX IF NOT EXISTS idx_app_companies_source     ON app_companies ("source", "source_id");
CREATE INDEX IF NOT EXISTS idx_app_companies_company_id        ON app_companies ("company_id");

CREATE INDEX IF NOT EXISTS idx_app_companies_revenue    ON app_companies ("revenue_usd");
CREATE INDEX IF NOT EXISTS idx_app_companies_employees  ON app_companies ("employee_count");
CREATE INDEX IF NOT EXISTS idx_app_companies_revrange   ON app_companies ("revenue_range");
CREATE INDEX IF NOT EXISTS idx_app_companies_emprange   ON app_companies ("employee_range");
CREATE INDEX IF NOT EXISTS idx_app_companies_tags       ON app_companies USING GIN ("industry_tags");
CREATE INDEX IF NOT EXISTS idx_app_companies_primind    ON app_companies ("primary_industry");
CREATE INDEX IF NOT EXISTS idx_app_companies_markets    ON app_companies USING GIN ("markets");
CREATE INDEX IF NOT EXISTS idx_app_companies_country    ON app_companies ("hq_country");

-- pg_trgm speeds up the phase-01/02 `primary_industry ILIKE '%term%'` matching
-- (leading-wildcard ILIKE can't use a plain B-tree index; this scales past 54k rows).
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_app_companies_primind_trgm
    ON app_companies USING GIN ("primary_industry" gin_trgm_ops);

COMMIT;
