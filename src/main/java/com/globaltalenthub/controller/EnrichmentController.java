package com.globaltalenthub.controller;

import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.EnrichmentService;
import com.globaltalenthub.service.EnrichmentService.ImportResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Clockwork enrichment endpoints. Port of enrichment.ts.
 *
 * <p>{@code import-candidate} is fully implemented (LLM company research + idempotent
 * executive attach). The candidate-matching trio (match / confirm / create-from-clockwork)
 * depends on the live Clockwork orchestration pipeline and is surfaced as
 * {@code 501 NOT_IMPLEMENTED} pending that port + live credentials.
 */
@RestController
@RequiredArgsConstructor
public class EnrichmentController {

    private final EnrichmentService enrichmentService;

    @PostMapping("/api/enrichment/import-candidate")
    public ImportResult importCandidate(@RequestBody Map<String, Object> body,
                                        @AuthenticationPrincipal AuthenticatedUser user) {
        return enrichmentService.importCandidate(body, user.orgId());
    }

    @PostMapping("/api/enrichment/match")
    public void match() {
        throw notImplemented();
    }

    @PostMapping("/api/enrichment/confirm")
    public void confirm() {
        throw notImplemented();
    }

    @PostMapping("/api/enrichment/create-from-clockwork")
    public void createFromClockwork() {
        throw notImplemented();
    }

    private static ResponseStatusException notImplemented() {
        return new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
            "Clockwork candidate matching requires the live Clockwork orchestration pipeline");
    }
}
