package com.globaltalenthub.controller;

import com.globaltalenthub.dto.ClientDto;
import com.globaltalenthub.dto.NewClientInput;
import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.AppClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ALAC Talent Map — the org's own client records (the client picker). Org-scoped;
 * {@code /api/app/**} already requires a valid JWT.
 */
@RestController
@RequiredArgsConstructor
public class AppClientController {

    private final AppClientService clientService;

    @GetMapping("/api/app/clients")
    public List<ClientDto> list(@RequestParam(required = false) String q,
                                @AuthenticationPrincipal AuthenticatedUser user) {
        return clientService.search(q, user);
    }

    @PostMapping("/api/app/clients")
    @ResponseStatus(HttpStatus.CREATED)
    public ClientDto create(@RequestBody NewClientInput req,
                            @AuthenticationPrincipal AuthenticatedUser user) {
        return clientService.create(req, user);
    }

    @GetMapping("/api/app/clients/{id}")
    public ClientDto get(@PathVariable Long id,
                         @AuthenticationPrincipal AuthenticatedUser user) {
        return clientService.get(id, user);
    }
}
