package com.globaltalenthub.service.pipeline;

import com.globaltalenthub.service.pipeline.CompanyScore.ScoredMatch;

/**
 * An enriched-company row tagged with how it matched the query and its 0..100
 * match score. Mirrors EnrichedCompanyMatch in server/storage/types.ts.
 */
public record EnrichedCompanyMatch(EnrichedRow row, ScoredMatch scored) {

    public int matchScore() {
        return scored.matchScore();
    }

    public String relevanceType() {
        return scored.relevanceType();
    }
}
