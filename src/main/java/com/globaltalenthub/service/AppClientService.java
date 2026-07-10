package com.globaltalenthub.service;

import com.globaltalenthub.dto.AppCompanyDto;
import com.globaltalenthub.dto.ClientDto;
import com.globaltalenthub.dto.ClientRef;
import com.globaltalenthub.dto.NewClientInput;
import com.globaltalenthub.entity.AppClient;
import com.globaltalenthub.repository.AppClientRepository;
import com.globaltalenthub.repository.AppCompanyRepository;
import com.globaltalenthub.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The org's own client records ({@code app_clients}). A client always has a name; a
 * catalog link ({@code linkedCompanyId → app_companies}) is optional display enrichment.
 */
@Service
@RequiredArgsConstructor
public class AppClientService {

    private final AppClientRepository clientRepo;
    private final AppCompanyRepository companyRepo;

    /**
     * Resolve the client for a project create: an existing {@code clientId} OR a
     * {@code newClient} to create — exactly one must be set.
     */
    @Transactional
    public AppClient resolveOrCreate(ClientRef ref, AuthenticatedUser user) {
        boolean hasId = ref != null && ref.clientId() != null;
        boolean hasNew = ref != null && ref.newClient() != null;
        if (hasId == hasNew) { // neither or both
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Provide exactly one of client.clientId or client.newClient");
        }
        if (hasId) {
            return clientRepo.findByIdAndOrgId(ref.clientId(), user.orgId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown clientId"));
        }
        return createEntity(ref.newClient(), user);
    }

    @Transactional
    public ClientDto create(NewClientInput input, AuthenticatedUser user) {
        return toDto(createEntity(input, user));
    }

    private AppClient createEntity(NewClientInput input, AuthenticatedUser user) {
        if (input == null || input.name() == null || input.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Client name is required");
        }
        String domain = normalizeDomain(input.domain());
        Long linkedId = input.linkedCompanyId();
        if (linkedId != null) {
            if (!companyRepo.existsById(linkedId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown linkedCompanyId");
            }
        } else if (domain != null) {
            // no explicit link: try to auto-match a catalog company by domain
            linkedId = companyRepo.findByDomainIgnoreCase(domain).stream()
                .findFirst().map(c -> c.getId()).orElse(null);
        }
        if (linkedId != null) {
            // reuse an existing linked client for this org (UNIQUE(org_id, linked_company_id))
            var existing = clientRepo.findByOrgIdAndLinkedCompanyId(user.orgId(), linkedId);
            if (existing.isPresent()) return existing.get();
        }
        AppClient c = new AppClient();
        c.setOrgId(user.orgId());
        c.setCreatedBy(user.userId());
        c.setName(input.name().trim());
        c.setDomain(domain);
        c.setLinkedCompanyId(linkedId);
        return clientRepo.save(c);
    }

    /** Trim and lowercase a domain, stripping any scheme/path/www prefix; null/blank → null. */
    private static String normalizeDomain(String raw) {
        if (raw == null) return null;
        String d = raw.trim().toLowerCase();
        if (d.isEmpty()) return null;
        d = d.replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        int slash = d.indexOf('/');
        if (slash >= 0) d = d.substring(0, slash);
        return d.isEmpty() ? null : d;
    }

    @Transactional(readOnly = true)
    public ClientDto get(Long id, AuthenticatedUser user) {
        return toDto(clientRepo.findByIdAndOrgId(id, user.orgId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found")));
    }

    @Transactional(readOnly = true)
    public List<ClientDto> search(String q, AuthenticatedUser user) {
        List<AppClient> rows = (q == null || q.isBlank())
            ? clientRepo.findByOrgId(user.orgId())
            : clientRepo.searchByName(user.orgId(), q.trim());
        return rows.stream().map(this::toDto).toList();
    }

    /** Build a ClientDto, inlining the linked catalog company when the link is set. */
    public ClientDto toDto(AppClient c) {
        AppCompanyDto linked = null;
        if (c.getLinkedCompanyId() != null) {
            linked = companyRepo.findById(c.getLinkedCompanyId()).map(AppCompanyDto::from).orElse(null);
        }
        return ClientDto.of(c, linked);
    }
}
