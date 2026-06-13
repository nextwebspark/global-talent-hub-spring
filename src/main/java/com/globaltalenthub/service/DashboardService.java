package com.globaltalenthub.service;

import com.globaltalenthub.entity.Company;
import com.globaltalenthub.entity.Executive;
import com.globaltalenthub.entity.Remuneration;
import com.globaltalenthub.entity.SearchQuery;
import com.globaltalenthub.repository.CompanyRepository;
import com.globaltalenthub.repository.ExecutiveRepository;
import com.globaltalenthub.repository.RemunerationRepository;
import com.globaltalenthub.repository.SearchQueryRepository;
import com.globaltalenthub.service.pipeline.LlmClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dashboard analytics aggregation. Port of dashboard.ts. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private static final String TITLE_PROMPT =
        "You are a title generator. Given a search query from an executive search firm, produce a short, "
        + "clean report title (5-12 words max) that describes what the talent mapping exercise covers. Use title "
        + "case. Do not include numbers like 'Top 10'. Just the subject matter. Return ONLY the title.";

    static final Map<String, List<String>> REGION_DEFINITIONS = new LinkedHashMap<>();
    static {
        REGION_DEFINITIONS.put("GCC", List.of("UAE", "United Arab Emirates", "Saudi Arabia", "Qatar", "Bahrain", "Kuwait", "Oman"));
        REGION_DEFINITIONS.put("Europe", List.of("United Kingdom", "UK", "France", "Germany", "Italy", "Spain", "Netherlands", "Belgium", "Switzerland", "Austria", "Sweden", "Norway", "Denmark", "Finland", "Ireland", "Portugal", "Poland", "Czech Republic", "Greece"));
        REGION_DEFINITIONS.put("Asia Pacific", List.of("China", "Japan", "South Korea", "India", "Australia", "New Zealand", "Singapore", "Hong Kong", "Taiwan", "Malaysia", "Thailand", "Indonesia", "Philippines", "Vietnam", "Pakistan"));
        REGION_DEFINITIONS.put("Middle East & North Africa", List.of("UAE", "United Arab Emirates", "Saudi Arabia", "Qatar", "Bahrain", "Kuwait", "Oman", "Egypt", "Morocco", "Tunisia", "Algeria", "Jordan", "Lebanon", "Iraq", "Iran", "Israel", "Turkey"));
        REGION_DEFINITIONS.put("Sub-Saharan Africa", List.of("Nigeria", "South Africa", "Kenya", "Ghana", "Ethiopia", "Tanzania", "Uganda", "Rwanda"));
        REGION_DEFINITIONS.put("Americas", List.of("United States", "USA", "Canada", "Mexico", "Brazil", "Argentina", "Chile", "Colombia", "Peru"));
    }

    private record Band(String label, double min, double max) {}

    private static final List<Band> REVENUE_BANDS = List.of(
        new Band("<$100M", 0, 100_000_000d),
        new Band("$100M–$500M", 100_000_000d, 500_000_000d),
        new Band("$500M–$1B", 500_000_000d, 1_000_000_000d),
        new Band("$1B–$5B", 1_000_000_000d, 5_000_000_000d),
        new Band("$5B+", 5_000_000_000d, Double.POSITIVE_INFINITY));

    private static final List<String> LEVEL_ORDER = List.of("Board", "C-Suite", "N-1", "N-2");
    private static final List<String> CATEGORY_KEYS = List.of("fixedFees", "allowances", "variableBonus", "ltip", "totalPackage");

    private final SearchQueryRepository searchQueryRepo;
    private final CompanyRepository companyRepo;
    private final ExecutiveRepository executiveRepo;
    private final RemunerationRepository remunerationRepo;
    private final CurrencyConversionService currency;
    private final LlmClassifier classifier;

    private static String getRevenueBand(Double rev) {
        if (rev == null || rev <= 0) return "Unknown";
        for (Band b : REVENUE_BANDS) {
            if (rev >= b.min() && rev < b.max()) return b.label();
        }
        return "Unknown";
    }

    public Map<String, Object> dashboard(Long searchId, String orgId) {
        SearchQuery sq = searchQueryRepo.findByIdAndOrgId(searchId, orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Search not found"));
        List<Company> companies = companyRepo.findBySearchQueryIdAndOrgId(searchId, orgId);

        // Load executives per company.
        Map<Long, List<Executive>> execsByCompany = new LinkedHashMap<>();
        for (Company c : companies) {
            execsByCompany.put(c.getId(), executiveRepo.findByCompanyIdAndOrgId(c.getId(), orgId));
        }

        String rawQuery = sq.getQuery() == null ? "" : sq.getQuery();
        String reportTitle = generateTitle(rawQuery);

        int totalCompanies = companies.size();
        int mappedCount = (int) companies.stream().filter(c -> !execsByCompany.get(c.getId()).isEmpty()).count();
        int completionPct = totalCompanies > 0 ? Math.round(mappedCount * 100f / totalCompanies) : 0;

        Map<String, Map<String, Integer>> countryCompletion = new LinkedHashMap<>();
        Map<String, Integer> companiesByCountry = new LinkedHashMap<>();
        for (Company c : companies) {
            String country = orDefault(c.getCountry(), "Unknown");
            countryCompletion.computeIfAbsent(country, k -> new LinkedHashMap<>(Map.of("total", 0, "mapped", 0)));
            countryCompletion.get(country).merge("total", 1, Integer::sum);
            companiesByCountry.merge(country, 1, Integer::sum);
            if (!execsByCompany.get(c.getId()).isEmpty()) countryCompletion.get(country).merge("mapped", 1, Integer::sum);
        }
        long distinctCountries = companiesByCountry.keySet().stream().filter(c -> !c.equals("Unknown")).count();
        String originCountry = companiesByCountry.entrySet().stream()
            .filter(e -> !e.getKey().equals("Unknown"))
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("Unknown");

        // Flatten executives with company context.
        record ExecCtx(Executive e, String country, Double revenue) {}
        List<ExecCtx> allExecutives = new ArrayList<>();
        for (Company c : companies) {
            Double rev = c.getRevenue() != null ? c.getRevenue().doubleValue() : null;
            for (Executive e : execsByCompany.get(c.getId())) {
                allExecutives.add(new ExecCtx(e, orDefault(c.getCountry(), "Unknown"), rev));
            }
        }
        int totalExecutives = allExecutives.size();

        Map<String, Integer> titleBreakdown = new LinkedHashMap<>();
        Map<String, Integer> countryExecBreakdown = new LinkedHashMap<>();
        for (ExecCtx x : allExecutives) {
            titleBreakdown.merge(level(x.e()), 1, Integer::sum);
            countryExecBreakdown.merge(x.country(), 1, Integer::sum);
        }

        Map<String, Integer> revenueBands = new LinkedHashMap<>();
        revenueBands.put("Unknown", 0);
        REVENUE_BANDS.forEach(b -> revenueBands.put(b.label(), 0));
        for (Company c : companies) {
            revenueBands.merge(getRevenueBand(c.getRevenue() != null ? c.getRevenue().doubleValue() : null), 1, Integer::sum);
        }

        Map<String, Integer> sectorBreakdown = new LinkedHashMap<>();
        Map<String, Integer> ownershipBreakdown = new LinkedHashMap<>();
        for (Company c : companies) {
            sectorBreakdown.merge(orDefault(c.getSector(), "Unknown"), 1, Integer::sum);
            ownershipBreakdown.merge(orDefault(c.getOwnershipType(), "Unknown"), 1, Integer::sum);
        }

        // Concentration index — top-3 exec geographies.
        List<Map.Entry<String, Integer>> sortedExecCountries = countryExecBreakdown.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).toList();
        int top3Share = sortedExecCountries.stream().limit(3).mapToInt(Map.Entry::getValue).sum();
        int top3Pct = totalExecutives > 0 ? Math.round(top3Share * 100f / totalExecutives) : 0;
        String concentrationLabel = top3Pct >= 80 ? "Concentrated" : top3Pct >= 50 ? "Moderate" : "Diversified";
        List<Map<String, Object>> topGeographies = sortedExecCountries.stream().limit(3)
            .map(e -> Map.<String, Object>of("country", e.getKey(), "count", e.getValue(),
                "pct", totalExecutives > 0 ? Math.round(e.getValue() * 100f / totalExecutives) : 0))
            .toList();

        // Remuneration aggregation (converted to USD).
        Map<String, CategoryBreakdown> remByLevel = new LinkedHashMap<>();
        Map<String, CategoryBreakdown> remByGeo = new LinkedHashMap<>();
        CategoryBreakdown overall = new CategoryBreakdown();
        List<Map<String, Object>> compRevenueEntries = new ArrayList<>();

        List<Long> execIds = allExecutives.stream().map(x -> x.e().getId()).toList();
        if (!execIds.isEmpty()) {
            Map<Long, ExecCtx> execMap = new LinkedHashMap<>();
            allExecutives.forEach(x -> execMap.put(x.e().getId(), x));
            for (Remuneration r : remunerationRepo.findByExecutiveIdIn(execIds)) {
                String code = currency.normalizeCurrencyCode(r.getCurrency());
                double base = r.getBaseSalary() != null ? currency.convertToUSD(r.getBaseSalary().doubleValue(), code) : 0;
                double allow = r.getTotalAllowances() != null ? currency.convertToUSD(r.getTotalAllowances().doubleValue(), code) : 0;
                double bon = r.getBonus() != null ? currency.convertToUSD(r.getBonus().doubleValue(), code) : 0;
                double ltip = r.getLongTermIncentives() != null ? currency.convertToUSD(r.getLongTermIncentives().doubleValue(), code) : 0;
                double total = base + allow + bon + ltip;
                if (total <= 0) continue;
                ExecCtx ctx = execMap.get(r.getExecutiveId());
                if (ctx == null) continue;
                String lvl = level(ctx.e());
                remByLevel.computeIfAbsent(lvl, k -> new CategoryBreakdown()).add(base, allow, bon, ltip, total);
                remByGeo.computeIfAbsent(ctx.country(), k -> new CategoryBreakdown()).add(base, allow, bon, ltip, total);
                overall.add(base, allow, bon, ltip, total);
                compRevenueEntries.add(Map.of("fixedFees", base, "allowances", allow, "variableBonus", bon,
                    "ltip", ltip, "totalPackage", total, "band", getRevenueBand(ctx.revenue()), "country", ctx.country()));
            }
        }

        Map<String, Object> remLevelStats = new LinkedHashMap<>();
        remByLevel.forEach((k, v) -> remLevelStats.put(k, v.stats()));
        Map<String, Object> remGeoStats = new LinkedHashMap<>();
        remByGeo.forEach((k, v) -> remGeoStats.put(k, v.stats()));

        Map<String, Object> stepUpAnalysis = new LinkedHashMap<>();
        for (String cat : CATEGORY_KEYS) {
            stepUpAnalysis.put(cat, buildStepUp(cat, remByLevel));
        }

        // Availability + diversity.
        int[] companyScopeCounts = new int[2]; // [outOfScope, offLimits]
        for (Company c : companies) {
            String cs = orDefault(c.getStatus(), "").trim().toLowerCase();
            if (cs.equals("out of scope")) companyScopeCounts[0]++;
            else if (cs.equals("off-limits")) companyScopeCounts[1]++;
        }
        int availableCount = 0, outOfScopeCount = 0, offLimitsCount = 0;
        Map<String, Map<String, Integer>> availByLevel = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> availByGeo = new LinkedHashMap<>();
        Map<String, Integer> genderBreakdown = new LinkedHashMap<>(Map.of("Male", 0, "Female", 0, "Non-Binary", 0, "Unknown", 0));
        Map<String, Integer> ethnicityBreakdown = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> genderByLevel = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> ethnicityByLevel = new LinkedHashMap<>();

        for (ExecCtx x : allExecutives) {
            String lvl = level(x.e());
            String country = x.country();
            String gender = orDefault(x.e().getGender(), "Unknown").trim();
            String ethnicity = orDefault(x.e().getEthnicity(), "Unknown").trim();
            genderBreakdown.merge(gender, 1, Integer::sum);
            ethnicityBreakdown.merge(ethnicity, 1, Integer::sum);
            genderByLevel.computeIfAbsent(lvl, k -> new LinkedHashMap<>(Map.of("Male", 0, "Female", 0, "Non-Binary", 0, "Unknown", 0))).merge(gender, 1, Integer::sum);
            ethnicityByLevel.computeIfAbsent(lvl, k -> new LinkedHashMap<>()).merge(ethnicity, 1, Integer::sum);
            availByLevel.computeIfAbsent(lvl, k -> new LinkedHashMap<>(Map.of("total", 0, "available", 0))).merge("total", 1, Integer::sum);
            availByGeo.computeIfAbsent(country, k -> new LinkedHashMap<>(Map.of("total", 0, "available", 0))).merge("total", 1, Integer::sum);
            String avail = orDefault(x.e().getAvailability(), "").toLowerCase().trim();
            if (avail.equals("interested")) {
                availableCount++;
                availByLevel.get(lvl).merge("available", 1, Integer::sum);
                availByGeo.get(country).merge("available", 1, Integer::sum);
            } else if (avail.equals("out of scope")) {
                outOfScopeCount++;
            } else if (avail.equals("off-limits")) {
                offLimitsCount++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportTitle", reportTitle);
        out.put("originCountry", originCountry);
        out.put("availableCountries", companiesByCountry.keySet().stream().filter(c -> !c.equals("Unknown")).sorted().toList());
        out.put("availableRegions", REGION_DEFINITIONS.keySet().stream().sorted().toList());
        out.put("regionDefinitions", REGION_DEFINITIONS);
        out.put("revenueBandLabels", REVENUE_BANDS.stream().map(Band::label).toList());
        out.put("distinctCountries", distinctCountries);
        out.put("mappingCompletion", Map.of("totalCompanies", totalCompanies, "mappedCount", mappedCount,
            "completionPct", completionPct, "byCountry", countryCompletion));
        out.put("executiveUniverse", Map.of("totalExecutives", totalExecutives, "byTitle", titleBreakdown, "byCountry", countryExecBreakdown));
        out.put("remuneration", Map.of("overall", overall.stats(), "byLevel", remLevelStats, "byGeography", remGeoStats,
            "currency", "USD", "stepUpAnalysis", stepUpAnalysis, "compRevenueEntries", compRevenueEntries));
        out.put("revenueBands", revenueBands);
        out.put("sectorBreakdown", sectorBreakdown);
        out.put("ownershipBreakdown", ownershipBreakdown);
        out.put("concentrationIndex", Map.of("label", concentrationLabel, "top3Pct", top3Pct, "topGeographies", topGeographies));
        Map<String, Object> availability = new LinkedHashMap<>();
        availability.put("totalExecutives", totalExecutives);
        availability.put("availableCount", availableCount);
        availability.put("availabilityPct", totalExecutives > 0 ? Math.round(availableCount * 100f / totalExecutives) : 0);
        availability.put("outOfScopeCount", outOfScopeCount);
        availability.put("offLimitsCount", offLimitsCount);
        availability.put("companyOutOfScopeCount", companyScopeCounts[0]);
        availability.put("companyOffLimitsCount", companyScopeCounts[1]);
        availability.put("byLevel", availByLevel);
        availability.put("byGeography", availByGeo);
        out.put("availability", availability);
        out.put("diversity", Map.of("genderBreakdown", genderBreakdown, "ethnicityBreakdown", ethnicityBreakdown,
            "genderByLevel", genderByLevel, "ethnicityByLevel", ethnicityByLevel));
        return out;
    }

    private String generateTitle(String rawQuery) {
        if (rawQuery.isBlank()) return rawQuery;
        try {
            String generated = classifier.classify(TITLE_PROMPT + "\n\nQuery: " + rawQuery);
            if (generated != null) {
                generated = generated.trim();
                if (generated.length() > 3 && generated.length() < 100) return generated;
            }
        } catch (Exception e) {
            log.warn("[Dashboard] title generation failed: {}", e.getMessage());
        }
        return rawQuery;
    }

    private List<Map<String, Object>> buildStepUp(String catKey, Map<String, CategoryBreakdown> byLevel) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String level : LEVEL_ORDER) {
            CategoryBreakdown bd = byLevel.get(level);
            if (bd == null) continue;
            Map<String, Long> stats = bd.statForCategory(catKey);
            if (stats.get("count") > 0) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("level", level);
                entry.put("median", stats.get("median"));
                entry.put("count", stats.get("count"));
                entries.add(entry);
            }
        }
        for (int i = 1; i < entries.size(); i++) {
            long higher = (long) entries.get(i - 1).get("median");
            long lower = (long) entries.get(i).get("median");
            if (lower > 0) {
                entries.get(i).put("stepUpPct", Math.round((higher - lower) * 100f / lower));
                entries.get(i).put("stepUpFrom", entries.get(i - 1).get("level"));
            }
        }
        return entries;
    }

    private static String level(Executive e) {
        return orDefault(e.getLevel(), "").trim().isEmpty() ? "Unassigned" : e.getLevel().trim();
    }

    private static String orDefault(String v, String d) {
        return v == null || v.isEmpty() ? d : v;
    }

    /** Accumulates compensation values per category and computes min/median/max/count. */
    private static final class CategoryBreakdown {
        final List<Double> fixedFees = new ArrayList<>();
        final List<Double> allowances = new ArrayList<>();
        final List<Double> variableBonus = new ArrayList<>();
        final List<Double> ltip = new ArrayList<>();
        final List<Double> totalPackage = new ArrayList<>();

        void add(double base, double allow, double bon, double lt, double total) {
            if (base > 0) fixedFees.add(base);
            if (allow > 0) allowances.add(allow);
            if (bon > 0) variableBonus.add(bon);
            if (lt > 0) ltip.add(lt);
            totalPackage.add(total);
        }

        Map<String, Object> stats() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fixedFees", computeStats(fixedFees));
            m.put("allowances", computeStats(allowances));
            m.put("variableBonus", computeStats(variableBonus));
            m.put("ltip", computeStats(ltip));
            m.put("totalPackage", computeStats(totalPackage));
            return m;
        }

        Map<String, Long> statForCategory(String key) {
            List<Double> values = switch (key) {
                case "fixedFees" -> fixedFees;
                case "allowances" -> allowances;
                case "variableBonus" -> variableBonus;
                case "ltip" -> ltip;
                default -> totalPackage;
            };
            return computeStats(values);
        }

        private static Map<String, Long> computeStats(List<Double> values) {
            if (values.isEmpty()) return new LinkedHashMap<>(Map.of("min", 0L, "median", 0L, "max", 0L, "count", 0L));
            List<Double> sorted = new ArrayList<>(values);
            sorted.sort(Double::compareTo);
            int mid = sorted.size() / 2;
            double median = sorted.size() % 2 == 0 ? (sorted.get(mid - 1) + sorted.get(mid)) / 2 : sorted.get(mid);
            Map<String, Long> m = new LinkedHashMap<>();
            m.put("min", Math.round(sorted.get(0)));
            m.put("median", Math.round(median));
            m.put("max", Math.round(sorted.get(sorted.size() - 1)));
            m.put("count", (long) sorted.size());
            return m;
        }
    }
}
