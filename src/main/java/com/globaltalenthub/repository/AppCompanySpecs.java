package com.globaltalenthub.repository;

import com.globaltalenthub.entity.AppCompany;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Composable filter for the company search. Each filter is skipped when its input
 * is empty. Fields AND together; repeated values within a field OR together.
 *
 * <p>Uses {@code LOWER(col) LIKE LOWER('%term%')} (portable — no Postgres-only
 * {@code ILIKE}) so the query is engine-agnostic. Industry matches
 * {@code primary_industry} by substring because phase-02's LLM emits free-text
 * industry terms and {@code primary_industry} has ~523 distinct raw labels.
 */
public final class AppCompanySpecs {

    private AppCompanySpecs() {}

    public static Specification<AppCompany> build(String q,
                                                  List<String> industries,
                                                  List<String> countries,
                                                  List<String> revenueRanges,
                                                  List<String> employeeRanges) {
        return (root, query, cb) -> {
            List<Predicate> and = new ArrayList<>();

            // q → LIKE on name OR search_text
            if (isSet(q)) {
                String like = "%" + q.toLowerCase() + "%";
                and.add(cb.or(
                    cb.like(cb.lower(root.get("name")), like),
                    cb.like(cb.lower(root.get("searchText")), like)));
            }

            // industry → OR of LOWER(primary_industry) LIKE %term% (free-text substring)
            List<String> ind = clean(industries);
            if (!ind.isEmpty()) {
                List<Predicate> ors = new ArrayList<>();
                for (String term : ind) {
                    ors.add(cb.like(cb.lower(root.get("primaryIndustry")),
                        "%" + term.toLowerCase() + "%"));
                }
                and.add(cb.or(ors.toArray(new Predicate[0])));
            }

            // country / revenueRange / employeeRange → exact IN
            addIn(and, root.get("hqCountry"), clean(countries));
            addIn(and, root.get("revenueRange"), clean(revenueRanges));
            addIn(and, root.get("employeeRange"), clean(employeeRanges));

            return and.isEmpty() ? cb.conjunction() : cb.and(and.toArray(new Predicate[0]));
        };
    }

    private static void addIn(List<Predicate> and, jakarta.persistence.criteria.Path<?> path,
                              List<String> values) {
        if (!values.isEmpty()) {
            and.add(path.in(values));
        }
    }

    private static List<String> clean(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in != null) {
            for (String s : in) {
                if (isSet(s)) out.add(s.trim());
            }
        }
        return out;
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }
}
