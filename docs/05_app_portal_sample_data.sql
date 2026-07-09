-- Sample/dev seed data for the docs/v2 search -> universe -> project flow.
-- DEV/TEST ONLY -- do not apply to prod.
--
-- Uses synthetic app_companies rows (source='sample') so this file is self-contained and
-- does not require the real 54,044-row BrightData catalog to be loaded first. Safe to
-- re-run: upserts by (source, source_id) and skips the org rows if already present.

BEGIN;

INSERT INTO app_companies (source, source_id, name, primary_industry, hq_country, hq_city,
    revenue_usd, revenue_range, employee_count, employee_range, is_public, description, search_text)
VALUES
    ('sample', 'sample-almarai',   'Almarai',   'FMCG', 'SA', 'Riyadh',
     4200000000, '1B-5B',   10500, '10000+',    true,
     'Dairy and food products manufacturer.', 'almarai fmcg dairy saudi arabia'),
    ('sample', 'sample-alrabie',   'Al Rabie Saudi Foods Co.', 'FMCG', 'SA', 'Jeddah',
     320000000,  '100M-500M', 2100, '1001-5000', false,
     'Juice and beverage manufacturer; also acts as a client company in this seed.',
     'al rabie fmcg beverages saudi arabia')
ON CONFLICT (source, source_id) DO UPDATE SET
    name = EXCLUDED.name,
    primary_industry = EXCLUDED.primary_industry;

-- Fixed dev UUIDs -- match TestIds-style constants if you wire this into integration tests.
-- org:  11111111-1111-1111-1111-111111111111
-- user: 22222222-2222-2222-2222-222222222222

INSERT INTO app_search_runs (org_id, created_by, query, mode, parsed_criteria, result_count, status)
SELECT '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222',
    'Top FMCG distributors in the UAE doing $1-5 billion', 'Search',
    '{"industry":["FMCG"],"country":["AE"],"revenueRange":["1B-5B"],"employeeRange":[]}'::jsonb,
    2, 'active'
WHERE NOT EXISTS (
    SELECT 1 FROM app_search_runs
    WHERE org_id = '11111111-1111-1111-1111-111111111111'
      AND query = 'Top FMCG distributors in the UAE doing $1-5 billion'
);

INSERT INTO app_projects (org_id, created_by, name, client_company_id, search_run_id, status)
SELECT '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222',
    'Top FMCG distributors in UAE',
    (SELECT id FROM app_companies WHERE source = 'sample' AND source_id = 'sample-alrabie'),
    (SELECT id FROM app_search_runs
        WHERE org_id = '11111111-1111-1111-1111-111111111111'
          AND query = 'Top FMCG distributors in the UAE doing $1-5 billion'),
    'active'
WHERE NOT EXISTS (
    SELECT 1 FROM app_projects
    WHERE org_id = '11111111-1111-1111-1111-111111111111'
      AND name = 'Top FMCG distributors in UAE'
);

INSERT INTO app_project_companies (org_id, project_id, company_id, relevance_type, confidence, status, map_x, map_y)
SELECT '11111111-1111-1111-1111-111111111111',
    (SELECT id FROM app_projects
        WHERE org_id = '11111111-1111-1111-1111-111111111111'
          AND name = 'Top FMCG distributors in UAE'),
    (SELECT id FROM app_companies WHERE source = 'sample' AND source_id = 'sample-almarai'),
    'Direct', 91, 'untriaged', 52.0, 50.0
WHERE NOT EXISTS (
    SELECT 1 FROM app_project_companies
    WHERE project_id = (SELECT id FROM app_projects
                         WHERE org_id = '11111111-1111-1111-1111-111111111111'
                           AND name = 'Top FMCG distributors in UAE')
      AND company_id = (SELECT id FROM app_companies WHERE source = 'sample' AND source_id = 'sample-almarai')
);

COMMIT;
