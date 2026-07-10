package com.globaltalenthub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.globaltalenthub.dto.CreateSearchRunRequest;
import com.globaltalenthub.dto.SearchCriteria;
import com.globaltalenthub.dto.SearchRunDto;
import com.globaltalenthub.entity.AppSearchRun;
import com.globaltalenthub.repository.AppSearchRunRepository;
import com.globaltalenthub.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Persist + read search runs. Create runs the LLM intent parse, merges the client's
 * (edited) criteria over it, and stores the merged criteria as jsonb.
 */
@Service
@RequiredArgsConstructor
public class AppSearchRunService {

    private final AppSearchRunRepository runRepo;
    private final AppSearchIntentService intentService;
    private final ObjectMapper objectMapper;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Transactional
    public SearchRunDto create(CreateSearchRunRequest req, AuthenticatedUser user) {
        String mode = req.mode();
        boolean importMode = "Import a list".equalsIgnoreCase(mode);
        if ((req.query() == null || req.query().isBlank()) && !importMode) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is required");
        }

        SearchCriteria llm = intentService.parse(req.query(), mode);
        SearchCriteria merged = SearchCriteria.merge(llm, req.criteria());

        AppSearchRun run = new AppSearchRun();
        run.setOrgId(user.orgId());
        run.setCreatedBy(user.userId());
        run.setQuery(req.query());
        run.setMode(mode);
        run.setParsedCriteria(toMap(merged));
        run = runRepo.save(run);
        return toDto(run);
    }

    @Transactional(readOnly = true)
    public SearchRunDto get(Long id, AuthenticatedUser user) {
        return toDto(load(id, user));
    }

    /**
     * Partial update of a run. Both args optional. {@code criteria} stores the user's edited
     * filters <b>verbatim</b> (no LLM re-parse, no merge — the client owns the full object at
     * this point, so every edit is honored); {@code resultCount} is the phase-01 write-back.
     */
    @Transactional
    public SearchRunDto patch(Long id, Integer resultCount, SearchCriteria criteria, AuthenticatedUser user) {
        AppSearchRun run = load(id, user);
        if (resultCount != null) {
            run.setResultCount(resultCount);
        }
        if (criteria != null) {
            run.setParsedCriteria(toMap(criteria));
        }
        return toDto(runRepo.save(run));
    }

    private AppSearchRun load(Long id, AuthenticatedUser user) {
        return runRepo.findByIdAndOrgId(id, user.orgId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Search run not found"));
    }

    private Map<String, Object> toMap(SearchCriteria c) {
        return objectMapper.convertValue(c, MAP_TYPE);
    }

    private SearchRunDto toDto(AppSearchRun run) {
        SearchCriteria criteria = run.getParsedCriteria() == null
            ? SearchCriteria.empty()
            : objectMapper.convertValue(run.getParsedCriteria(), SearchCriteria.class);
        return new SearchRunDto(run.getId(), run.getQuery(), run.getMode(), criteria,
            run.getResultCount(), run.getStatus(), run.getCreatedAt());
    }
}
