package com.globaltalenthub.controller;

import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.NotesService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Executive/company notes — GET latest, PUT upsert. Port of notes.ts. */
@RestController
@RequiredArgsConstructor
public class NotesController {

    private final NotesService service;

    public record NotesResponse(String content) {}

    @GetMapping("/api/executives/{id}/notes")
    public NotesResponse getExecutiveNotes(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return new NotesResponse(service.getExecutiveNotes(id, user.orgId()));
    }

    @PutMapping("/api/executives/{id}/notes")
    public NotesResponse putExecutiveNotes(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        String content = body.get("content") == null ? "" : body.get("content").toString();
        return new NotesResponse(service.putExecutiveNotes(id, content, user.orgId()));
    }

    @GetMapping("/api/companies/{id}/notes")
    public NotesResponse getCompanyNotes(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return new NotesResponse(service.getCompanyNotes(id, user.orgId()));
    }

    @PutMapping("/api/companies/{id}/notes")
    public NotesResponse putCompanyNotes(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                         @AuthenticationPrincipal AuthenticatedUser user) {
        String content = body.get("content") == null ? "" : body.get("content").toString();
        return new NotesResponse(service.putCompanyNotes(id, content, user.orgId()));
    }
}
