-- user_search.sql -- a narrated, step-by-step walkthrough of one user search turning into a
-- triaged project, using the exact SQL the backend generates at each phase (02 -> 01 -> 03 -> 04).
--
-- DEV/TEST ONLY. Not applied automatically, not idempotent (uses fixed demo ids on purpose so
-- the narration below matches exactly). Run against a DB that already has docs/04_app_companies.sql
-- and docs/05_app_portal.sql applied. Uses synthetic app_companies rows (source =
-- 'user_search_demo') so it doesn't depend on or collide with the real 54k-row catalog or with
-- docs/05_app_portal_sample_data.sql.
--
-- User's search text: "Top FMCG distributors in the UAE doing $1-5 billion"
-- org_id  = 11111111-1111-1111-1111-111111111111
-- user_id = 22222222-2222-2222-2222-222222222222

BEGIN;

-- ============================================================================
-- STEP 0 -- seed a small, self-contained slice of the master catalog
-- ============================================================================
INSERT INTO app_companies
    (source, source_id, name, primary_industry, hq_country, hq_city,
     revenue_usd, revenue_range, employee_count, employee_range, is_public, description, search_text)
VALUES
    ('user_search_demo', 'usd-101', 'Al Naboodah Group Enterprises', 'FMCG', 'AE', 'Dubai',
     2500000000, '1B-5B', 8200, '5001-10000', false,
     'Diversified FMCG distribution and trading group.', 'al naboodah fmcg distribution uae dubai'),
    ('user_search_demo', 'usd-102', 'Agthia Group', 'Food and Beverage', 'AE', 'Abu Dhabi',
     1400000000, '1B-5B', 3400, '1001-5000', true,
     'Food and beverage manufacturer and distributor.', 'agthia food beverage uae abu dhabi'),
    ('user_search_demo', 'usd-103', 'Almarai', 'FMCG', 'SA', 'Riyadh',
     4200000000, '1B-5B', 10500, '10000+', true,
     'Dairy and food products manufacturer.', 'almarai fmcg dairy saudi arabia'),
    ('user_search_demo', 'usd-104', 'Al Rabie Saudi Foods Co.', 'FMCG', 'SA', 'Jeddah',
     320000000, '100M-500M', 2100, '1001-5000', false,
     'Juice and beverage manufacturer; plays the CLIENT company in this walkthrough.',
     'al rabie fmcg beverages saudi arabia'),
    ('user_search_demo', 'usd-105', 'Emirates Global Aluminium', 'Metals and Mining', 'AE', 'Dubai',
     1800000000, '1B-5B', 7000, '5001-10000', false,
     'Aluminium producer -- wrong industry, proves the ILIKE filter excludes it.',
     'emirates global aluminium metals uae')
ON CONFLICT (source, source_id) DO UPDATE SET name = EXCLUDED.name;

-- STATE AFTER STEP 0 -- app_companies (5 rows; ids assigned by IDENTITY, substitute below)
-- Run: SELECT id, name, primary_industry, hq_country, revenue_range FROM app_companies WHERE source = 'user_search_demo' ORDER BY id;
--
--  id  | name                           | primary_industry   | hq_country | revenue_range
-- -----+--------------------------------+---------------------+------------+---------------
--  101 | Al Naboodah Group Enterprises  | FMCG                | AE         | 1B-5B
--  102 | Agthia Group                   | Food and Beverage   | AE         | 1B-5B
--  103 | Almarai                        | FMCG                | SA         | 1B-5B
--  104 | Al Rabie Saudi Foods Co.       | FMCG                | SA         | 100M-500M
--  105 | Emirates Global Aluminium      | Metals and Mining   | AE         | 1B-5B
--
-- (the walkthrough below assumes these ids are exactly 101-105; if your DB already has rows,
-- your IDENTITY sequence will assign different numbers -- adjust the ids in steps 2-6 to match.)


-- ============================================================================
-- PHASE 02 -- POST /api/app/search-runs {"query": "...", "mode": "Search"}
-- AppSearchIntentService calls the LLM (geminiFlash); it returns:
--   industry: ["FMCG", "Food and Beverage"], country: ["AE"], revenueRange: ["1B-5B"], employeeRange: []
-- AppSearchRunService.create() persists the run with that parsed_criteria.
-- ============================================================================
INSERT INTO app_search_runs (org_id, created_by, query, mode, parsed_criteria, status)
VALUES (
    '11111111-1111-1111-1111-111111111111',
    '22222222-2222-2222-2222-222222222222',
    'Top FMCG distributors in the UAE doing $1-5 billion',
    'Search',
    '{"industry":["FMCG","Food and Beverage"],"country":["AE"],"revenueRange":["1B-5B"],"employeeRange":[]}'::jsonb,
    'active'
) RETURNING id;
-- id = 501

-- STATE AFTER PHASE 02 -- app_search_runs (1 row)
--
--  id  | query                                             | mode   | parsed_criteria                                            | result_count | status
-- -----+---------------------------------------------------+--------+-------------------------------------------------------------+--------------+--------
--  501 | Top FMCG distributors in the UAE doing $1-5 billion| Search | {industry:[FMCG,Food and Beverage],country:[AE],...}         | NULL         | active
--
-- app_projects: empty.  app_project_companies: empty.


-- ============================================================================
-- PHASE 01 -- GET /api/app/companies/search?industry=FMCG&industry=Food and Beverage
--                &country=AE&revenueRange=1B-5B&sort=revenueUsd,desc&page=0&size=25
-- The UI runs this with exactly the parsed_criteria from the run above. Generated by
-- AppCompanyRepository.search(...): AND across fields, OR within a field (industry, country).
-- ============================================================================

-- main paged result:
SELECT id, name, primary_industry, hq_country, revenue_usd, revenue_range, employee_range
FROM app_companies
WHERE (primary_industry ILIKE '%FMCG%' OR primary_industry ILIKE '%Food and Beverage%')
  AND hq_country IN ('AE')
  AND revenue_range IN ('1B-5B')
ORDER BY revenue_usd DESC
LIMIT 25 OFFSET 0;

-- count query backing Page.totalElements:
SELECT count(*)
FROM app_companies
WHERE (primary_industry ILIKE '%FMCG%' OR primary_industry ILIKE '%Food and Beverage%')
  AND hq_country IN ('AE')
  AND revenue_range IN ('1B-5B');

-- STATE / RESULT -- 2 of the 5 seeded rows match (id 103 excluded: SA not AE; id 104
-- excluded: right country class but wrong range/country; id 105 excluded: wrong industry):
--
--  id  | name                          | primary_industry  | hq_country | revenue_usd
-- -----+-------------------------------+--------------------+------------+-------------
--  101 | Al Naboodah Group Enterprises | FMCG               | AE         | 2500000000
--  102 | Agthia Group                  | Food and Beverage  | AE         | 1400000000
--
-- totalElements = 2

-- GET /api/app/companies/facets (global, same shape regardless of the query above):
SELECT primary_industry AS value, count(*) FROM app_companies WHERE source = 'user_search_demo' GROUP BY primary_industry ORDER BY count(*) DESC;
SELECT hq_country       AS value, count(*) FROM app_companies WHERE source = 'user_search_demo' GROUP BY hq_country       ORDER BY count(*) DESC;
SELECT revenue_range    AS value, count(*) FROM app_companies WHERE source = 'user_search_demo' GROUP BY revenue_range    ORDER BY count(*) DESC;
SELECT employee_range   AS value, count(*) FROM app_companies WHERE source = 'user_search_demo' GROUP BY employee_range   ORDER BY count(*) DESC;


-- ============================================================================
-- Back to PHASE 02 -- PATCH /api/app/search-runs/501 {"resultCount": 2}
-- Written back once the phase-01 search above has resolved.
-- ============================================================================
UPDATE app_search_runs
SET result_count = 2, updated_at = now()
WHERE id = 501 AND org_id = '11111111-1111-1111-1111-111111111111';

-- STATE AFTER THIS PATCH -- app_search_runs
--
--  id  | result_count | status | updated_at
-- -----+--------------+--------+------------
--  501 | 2            | active | (refreshed)


-- ============================================================================
-- Before PHASE 03 -- the CLIENT PICKER. The client is a separate concept from the
-- universe: it's the company the project/search is being done FOR (here: Al Rabie, a
-- Saudi F&B company that wants a map of UAE FMCG distributors), not a member of the
-- filtered search results. So it is looked up independently -- reusing the phase-01
-- search endpoint, but with only `q` set and none of the country/industry/revenueRange
-- filters that produced the universe above. That's how id 104 (hq_country SA) can be
-- picked as the client even though the universe search was scoped to country=AE.
-- ============================================================================
SELECT id, name, primary_industry, hq_country, revenue_usd
FROM app_companies
WHERE (name ILIKE '%Al Rabie%' OR search_text ILIKE '%Al Rabie%')
ORDER BY revenue_usd DESC
LIMIT 10;
-- returns id 104 (Al Rabie Saudi Foods Co., SA) -- no country/industry/revenue filter applied here


-- ============================================================================
-- PHASE 03 -- user confirms the universe: having picked the CLIENT (Al Rabie, id 104)
-- via the lookup above, keeps both matched companies (101 Direct, 102 Adjacent) from the
-- universe search with map positions.
-- POST /api/app/projects { name, clientCompanyId: 104, searchRunId: 501, companies: [...] }
-- AppProjectService.create() -- @Transactional: one INSERT into app_projects, one
-- INSERT per selected company into app_project_companies.
-- ============================================================================
INSERT INTO app_projects (org_id, created_by, name, client_company_id, search_run_id, status)
VALUES (
    '11111111-1111-1111-1111-111111111111',
    '22222222-2222-2222-2222-222222222222',
    'Top FMCG distributors in UAE',
    104,
    501,
    'active'
) RETURNING id;
-- id = 77

INSERT INTO app_project_companies (org_id, project_id, company_id, relevance_type, confidence, status, map_x, map_y)
VALUES
    ('11111111-1111-1111-1111-111111111111', 77, 101, 'Direct',   93, 'untriaged', 55.0, 48.0),
    ('11111111-1111-1111-1111-111111111111', 77, 102, 'Adjacent', 78, 'untriaged', 40.0, 60.0);

-- STATE AFTER PHASE 03
--
-- app_projects (1 row):
--  id | name                          | client_company_id | search_run_id | status
-- ----+-------------------------------+--------------------+---------------+--------
--  77 | Top FMCG distributors in UAE  | 104 (Al Rabie)     | 501           | active
--
-- app_project_companies (2 rows):
--  id | project_id | company_id | relevance_type | confidence | status    | map_x | map_y
-- ----+------------+------------+-----------------+------------+-----------+-------+-------
--   1 | 77         | 101        | Direct          | 93         | untriaged | 55.0  | 48.0
--   2 | 77         | 102        | Adjacent         | 78         | untriaged | 40.0  | 60.0


-- ============================================================================
-- PHASE 04 -- open the project workspace: GET /api/app/projects/77/companies?page=0&size=25
-- Generated by AppProjectService.listCompanies(): page the join rows, join each to
-- app_companies for display fields.
-- ============================================================================
SELECT pc.id, pc.company_id, c.name, c.primary_industry, c.hq_country, c.revenue_usd,
       pc.relevance_type, pc.confidence, pc.status, pc.map_x, pc.map_y
FROM app_project_companies pc
JOIN app_companies c ON c.id = pc.company_id
WHERE pc.project_id = 77 AND pc.org_id = '11111111-1111-1111-1111-111111111111'
ORDER BY pc.id
LIMIT 25 OFFSET 0;

-- user triages the table: shortlists Al Naboodah, declines Agthia, drags Al Naboodah's bubble.
-- PATCH /api/app/projects/77/companies/101 {"status": "shortlisted"}
UPDATE app_project_companies
SET status = 'shortlisted'
WHERE project_id = 77 AND company_id = 101 AND org_id = '11111111-1111-1111-1111-111111111111';

-- PATCH /api/app/projects/77/companies/101 {"mapX": 61.5, "mapY": 42.0}
UPDATE app_project_companies
SET map_x = 61.5, map_y = 42.0
WHERE project_id = 77 AND company_id = 101 AND org_id = '11111111-1111-1111-1111-111111111111';

-- PATCH /api/app/projects/77/companies/102 {"status": "declined"}
UPDATE app_project_companies
SET status = 'declined'
WHERE project_id = 77 AND company_id = 102 AND org_id = '11111111-1111-1111-1111-111111111111';

-- STATE AFTER PHASE 04 -- app_project_companies (final)
--
--  id | company_id | name                          | relevance_type | confidence | status      | map_x | map_y
-- ----+------------+-------------------------------+-----------------+------------+-------------+-------+-------
--   1 | 101        | Al Naboodah Group Enterprises  | Direct          | 93         | shortlisted | 61.5  | 42.0
--   2 | 102        | Agthia Group                   | Adjacent        | 78         | declined    | 40.0  | 60.0

-- sidebar triage-bucket counts (GROUP BY status, per docs/v2/phase-00 + phase-04):
SELECT status, count(*)
FROM app_project_companies
WHERE project_id = 77
GROUP BY status;
-- shortlisted: 1, declined: 1

COMMIT;

-- ----------------------------------------------------------------------------
-- Cleanup (uncomment to remove everything this script created; run as its own statement,
-- not inside the transaction above, since app_projects.search_run_id/app_companies FKs are
-- ON DELETE RESTRICT and must be deleted child-first):
-- DELETE FROM app_project_companies WHERE project_id = 77;
-- DELETE FROM app_projects WHERE id = 77;
-- DELETE FROM app_search_runs WHERE id = 501;
-- DELETE FROM app_companies WHERE source = 'user_search_demo';
-- ----------------------------------------------------------------------------
