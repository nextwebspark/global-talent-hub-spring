package com.globaltalenthub.controller;

import com.globaltalenthub.entity.CareerHistory;
import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.CareerHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Career history — collection under an executive, items by id. Port of career.ts. */
@RestController
@RequiredArgsConstructor
public class CareerHistoryController {

    private final CareerHistoryService service;

    @GetMapping("/api/executives/{executiveId}/career-history")
    public List<CareerHistory> list(@PathVariable Long executiveId, @AuthenticationPrincipal AuthenticatedUser user) {
        return service.byExecutive(executiveId, user.orgId());
    }

    @PostMapping("/api/executives/{executiveId}/career-history")
    @ResponseStatus(HttpStatus.CREATED)
    public CareerHistory create(@PathVariable Long executiveId, @RequestBody CareerHistory entry,
                                @AuthenticationPrincipal AuthenticatedUser user) {
        return service.create(executiveId, entry, user.orgId());
    }

    @PatchMapping("/api/career-history/{id}")
    public CareerHistory update(@PathVariable Long id, @RequestBody Map<String, Object> patch,
                                @AuthenticationPrincipal AuthenticatedUser user) {
        return service.update(id, patch, user.orgId());
    }

    @DeleteMapping("/api/career-history/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(id, user.orgId());
    }
}
