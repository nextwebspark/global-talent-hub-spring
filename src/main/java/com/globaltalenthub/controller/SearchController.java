package com.globaltalenthub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globaltalenthub.entity.SearchQuery;
import com.globaltalenthub.entity.SearchSession;
import com.globaltalenthub.repository.SearchSessionRepository;
import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.BriefSummaryService;
import com.globaltalenthub.service.SearchManagementService;
import com.globaltalenthub.service.SearchQueryService;
import com.globaltalenthub.service.pipeline.BriefConfig;
import com.globaltalenthub.service.pipeline.EventSink;
import com.globaltalenthub.service.pipeline.SearchPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import com.globaltalenthub.service.BriefExtractService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SSE search endpoint — port of the enhanced-stream route in search.ts. Streams the
 * discovery/enrichment pipeline events to the browser EventSource.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class SearchController {

    private static final long SSE_TIMEOUT_MS = 300_000L; // 5 min
    private static final Pattern LIMIT_PATTERN =
        Pattern.compile("(?:top|first|leading|biggest|largest|best)\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    private final SearchPipelineService pipelineService;
    private final SearchQueryService searchQueryService;
    private final SearchSessionRepository searchSessionRepository;
    private final BriefSummaryService briefSummaryService;
    private final BriefExtractService briefExtractService;
    private final SearchManagementService searchManagementService;
    private final ObjectMapper objectMapper;

    @Qualifier("sseTaskExecutor")
    private final Executor sseTaskExecutor;

    public record BriefUploadResponse(String filename, String extractedText, int charCount) {}

    public record ConfidentialResponse(boolean ok, boolean pdConfidential) {}

    /** Extract text from an uploaded brief/PD and persist it (+confidentiality) to the session. */
    @PostMapping(value = "/api/search/upload-brief", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BriefUploadResponse uploadBrief(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "sessionId", required = false) String sessionId,
                                           @RequestParam(value = "pdConfidential", required = false, defaultValue = "false") boolean pdConfidential) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file uploaded");
        }
        String extractedText = briefExtractService.extract(file);

        if (sessionId != null && !sessionId.isBlank()) {
            try {
                SearchSession session = searchSessionRepository.findById(sessionId).orElseGet(() -> {
                    SearchSession s = new SearchSession();
                    s.setId(sessionId);
                    s.setRawQuery("");
                    s.setStatus("active");
                    return s;
                });
                session.setPdContent(extractedText);
                session.setPdConfidential(pdConfidential);
                if (session.getStatus() == null) session.setStatus("active");
                searchSessionRepository.save(session);
            } catch (Exception e) {
                log.warn("[Routes] Could not persist PD content to session: {}", e.getMessage());
            }
        }
        return new BriefUploadResponse(file.getOriginalFilename(), extractedText, extractedText.length());
    }

    /** Toggle confidentiality of a previously uploaded brief. */
    @PatchMapping("/api/search/session/{sessionId}/confidential")
    public ConfidentialResponse setConfidential(@PathVariable String sessionId,
                                                @RequestBody Map<String, Object> body) {
        Object flag = body.get("pdConfidential");
        if (!(flag instanceof Boolean confidential)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pdConfidential must be a boolean");
        }
        SearchSession session = searchSessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        session.setPdConfidential(confidential);
        searchSessionRepository.save(session);
        return new ConfidentialResponse(true, confidential);
    }

    @GetMapping(value = "/api/search/enhanced-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter enhancedStream(@RequestParam String query,
                                     @RequestParam String sessionId,
                                     @AuthenticationPrincipal AuthenticatedUser user,
                                     HttpServletResponse response) {
        // Proxy fix (Railway/Nginx buffer SSE without these). Content-Type set by produces.
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean aborted = new AtomicBoolean(false);
        emitter.onCompletion(() -> aborted.set(true));
        emitter.onTimeout(() -> aborted.set(true));
        emitter.onError(e -> aborted.set(true));

        UUID orgId = user.orgId();
        UUID userId = user.userId();

        sseTaskExecutor.execute(() -> {
            EventSink sink = new SseSink(emitter, sessionId, objectMapper, searchSessionRepository);
            try {
                String briefContext = buildBriefContext(sessionId);
                int limit = extractLimit(query);

                SearchQuery sq = searchQueryService.upsertForSession(query, sessionId, orgId, userId, null);

                SearchSession session = searchSessionRepository.findById(sessionId).orElseGet(() -> {
                    SearchSession s = new SearchSession();
                    s.setId(sessionId);
                    s.setRawQuery(query);
                    s.setStatus("active");
                    return s;
                });
                session.setRawQuery(query);
                if (session.getStatus() == null) session.setStatus("active");
                session.setSearchQueryId(sq.getId());
                searchSessionRepository.save(session);

                sink.emit("search_created", "Search created",
                    Map.of("searchQueryId", sq.getId(), "query", query, "sessionId", sessionId));

                pipelineService.runSeedListEnhancedStream(
                    query, sq.getId(), orgId, limit, aborted::get, sessionId, briefContext, sink);

                if (!aborted.get()) {
                    sink.emit("done", "Done", Map.of("searchQueryId", sq.getId()));
                }
                emitter.complete();
            } catch (Exception e) {
                log.error("[Routes] Enhanced stream error", e);
                if (!aborted.get()) {
                    try {
                        sink.emit("error", "Search failed",
                            Map.of("message", e.getMessage() == null ? "Search failed" : e.getMessage()));
                    } catch (Exception ignored) {
                        // emitter already closed
                    }
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    // ── Search-query management (non-SSE) ──────────────────────────────────────

    @GetMapping("/api/search-history")
    public List<SearchManagementService.HistoryEntry> history(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return searchManagementService.history(user.orgId());
    }

    @GetMapping("/api/search-history/{id}/load")
    public Map<String, Object> load(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        var results = searchManagementService.fullResults(id, user.orgId());
        var sq = results.searchQuery();
        String sessionId = results.companies().stream()
            .map(c -> c.company().getSearchSessionId())
            .filter(java.util.Objects::nonNull)
            .findFirst().orElse(null);
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("results", results.companies());
        out.put("searchQueryId", id);
        out.put("query", sq.getQuery());
        out.put("status", sq.getStatus());
        out.put("sessionId", sessionId);
        out.put("satelliteHierarchies", sq.getSatelliteHierarchies() != null ? sq.getSatelliteHierarchies() : Map.of());
        out.put("satelliteOrders", sq.getSatelliteOrders() != null ? sq.getSatelliteOrders() : Map.of());
        out.put("tableConfig", sq.getTableConfig());
        out.put("mapPositions", sq.getMapPositions() != null ? sq.getMapPositions() : Map.of());
        return out;
    }

    @GetMapping("/api/search-results/{id}")
    public Map<String, Object> searchResults(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        var results = searchManagementService.fullResults(id, user.orgId());
        var sq = results.searchQuery();
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("searchQuery", sq);
        out.put("companies", results.companies());
        out.put("satelliteHierarchies", sq.getSatelliteHierarchies() != null ? sq.getSatelliteHierarchies() : Map.of());
        out.put("satelliteOrders", sq.getSatelliteOrders() != null ? sq.getSatelliteOrders() : Map.of());
        return out;
    }

    @PutMapping("/api/search/{id}/satellite-hierarchies")
    public Map<String, Boolean> saveHierarchies(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        searchManagementService.saveSatelliteHierarchies(id, mapField(body, "hierarchies"), user.orgId());
        return Map.of("success", true);
    }

    @PutMapping("/api/search/{id}/satellite-orders")
    public Map<String, Boolean> saveOrders(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        searchManagementService.saveSatelliteOrders(id, mapField(body, "orders"), user.orgId());
        return Map.of("success", true);
    }

    @PutMapping("/api/search/{id}/map-positions")
    public Map<String, Boolean> savePositions(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                              @AuthenticationPrincipal AuthenticatedUser user) {
        searchManagementService.saveMapPositions(id, mapField(body, "positions"), user.orgId());
        return Map.of("success", true);
    }

    @PutMapping("/api/search/{id}/table-config")
    public Map<String, Boolean> saveTableConfig(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        searchManagementService.saveTableConfig(id, mapField(body, "config"), user.orgId());
        return Map.of("success", true);
    }

    @PostMapping("/api/search/add-to-project")
    public SearchManagementService.AddToProjectResult addToProject(
            @RequestBody Map<String, Object> body, @AuthenticationPrincipal AuthenticatedUser user) {
        List<Long> companyIds = longList(body.get("companyIds"));
        String sessionId = body.get("sessionId") instanceof String s ? s : null;
        Long searchQueryId = asLong(body.get("searchQueryId"));
        return searchManagementService.addToProject(companyIds, sessionId, searchQueryId, user.orgId());
    }

    // Coerce request values defensively → 400 on shape mismatch (not a 500 ClassCastException).
    private static List<Long> longList(Object v) {
        if (v == null) return null;
        if (!(v instanceof List<?> list)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "companyIds must be an array");
        }
        return list.stream().map(SearchController::asLong).toList();
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.valueOf(v.toString());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid numeric id");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapField(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (!(v instanceof Map)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + key + " data");
        }
        return (Map<String, Object>) v;
    }

    // Build the classifier brief context: confidential uploads are summarised to
    // neutral criteria; otherwise raw text is passed through, capped.
    private String buildBriefContext(String sessionId) {
        Optional<SearchSession> session = searchSessionRepository.findById(sessionId);
        if (session.isEmpty()) return null;
        String pdContent = session.get().getPdContent();
        if (pdContent == null || pdContent.isBlank()) return null;
        if (Boolean.TRUE.equals(session.get().getPdConfidential())) {
            String summary = briefSummaryService.summarize(pdContent);
            return summary.isBlank() ? null : summary;
        }
        return pdContent.substring(0, Math.min(pdContent.length(), BriefConfig.CLASSIFIER_CHAR_LIMIT));
    }

    // Extract a "top N" / "first N" limit; default 10, clamp 1..50.
    static int extractLimit(String query) {
        if (query == null) return 10;
        Matcher m = LIMIT_PATTERN.matcher(query);
        if (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                if (n >= 1 && n <= 50) return n;
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return 10;
    }

    /** EventSink that writes to the SseEmitter and persists intent on intent_extracted. */
    private static final class SseSink implements EventSink {
        private final SseEmitter emitter;
        private final String sessionId;
        private final ObjectMapper mapper;
        private final SearchSessionRepository sessionRepo;

        SseSink(SseEmitter emitter, String sessionId, ObjectMapper mapper, SearchSessionRepository sessionRepo) {
            this.emitter = emitter;
            this.sessionId = sessionId;
            this.mapper = mapper;
            this.sessionRepo = sessionRepo;
        }

        @Override
        public void emit(String type, String message, Object data) {
            try {
                java.util.Map<String, Object> payload = new java.util.HashMap<>();
                if (data instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> payload.put(String.valueOf(k), v));
                }
                payload.put("message", message);
                payload.put("timestamp", java.time.Instant.now().toString());
                emitter.send(SseEmitter.event().name(type).data(mapper.writeValueAsString(payload)));

                if ("intent_extracted".equals(type) && data instanceof Map<?, ?> m && m.get("intent") != null) {
                    try {
                        sessionRepo.updateInferredIntent(sessionId, mapper.writeValueAsString(m.get("intent")));
                    } catch (Exception e) {
                        // best-effort persistence; do not break the stream
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("SSE send failed: " + e.getMessage(), e);
            }
        }
    }
}
