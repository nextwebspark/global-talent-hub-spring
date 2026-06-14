package com.globaltalenthub.service;

import java.util.UUID;

import com.globaltalenthub.entity.CareerHistory;
import com.globaltalenthub.repository.CareerHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Career-history CRUD for an executive. Port of career.ts + executives.ts career endpoints. */
@Service
@RequiredArgsConstructor
public class CareerHistoryService {

    private final CareerHistoryRepository repo;
    private final OrgGuardService orgGuard;

    public List<CareerHistory> byExecutive(Long executiveId, UUID orgId) {
        orgGuard.assertExecutiveInOrg(executiveId, orgId);
        return repo.findByExecutiveIdOrderBySortOrderAsc(executiveId);
    }

    @Transactional
    public CareerHistory create(Long executiveId, CareerHistory entry, UUID orgId) {
        orgGuard.assertExecutiveInOrg(executiveId, orgId);
        entry.setExecutiveId(executiveId);
        return repo.save(entry);
    }

    @Transactional
    public CareerHistory update(Long id, Map<String, Object> patch, UUID orgId) {
        orgGuard.assertCareerHistoryInOrg(id, orgId);
        CareerHistory e = repo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Career history not found"));
        patch.forEach((k, v) -> {
            String s = v == null ? null : v.toString();
            switch (k) {
                case "company" -> e.setCompany(s);
                case "title" -> e.setTitle(s);
                case "startDate" -> e.setStartDate(s);
                case "endDate" -> e.setEndDate(s);
                case "description" -> e.setDescription(s);
                case "sortOrder" -> e.setSortOrder(v instanceof Number n ? n.intValue() : null);
                default -> { }
            }
        });
        return repo.save(e);
    }

    @Transactional
    public void delete(Long id, UUID orgId) {
        orgGuard.assertCareerHistoryInOrg(id, orgId);
        repo.deleteById(id);
    }
}
