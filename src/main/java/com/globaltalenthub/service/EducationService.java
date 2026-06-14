package com.globaltalenthub.service;

import java.util.UUID;

import com.globaltalenthub.entity.Education;
import com.globaltalenthub.repository.EducationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Education CRUD for an executive. Port of education.ts. */
@Service
@RequiredArgsConstructor
public class EducationService {

    private final EducationRepository repo;
    private final OrgGuardService orgGuard;

    public List<Education> byExecutive(Long executiveId, UUID orgId) {
        orgGuard.assertExecutiveInOrg(executiveId, orgId);
        return repo.findByExecutiveId(executiveId);
    }

    @Transactional
    public Education create(Long executiveId, Education entry, UUID orgId) {
        orgGuard.assertExecutiveInOrg(executiveId, orgId);
        entry.setExecutiveId(executiveId);
        return repo.save(entry);
    }

    @Transactional
    public Education update(Long id, Map<String, Object> patch, UUID orgId) {
        Education e = repo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Education not found"));
        orgGuard.assertExecutiveInOrg(e.getExecutiveId(), orgId);
        patch.forEach((k, v) -> {
            String s = v == null ? null : v.toString();
            switch (k) {
                case "institution" -> e.setInstitution(s);
                case "degree" -> e.setDegree(s);
                case "fieldOfStudy" -> e.setFieldOfStudy(s);
                case "graduationYear" -> e.setGraduationYear(s);
                default -> { }
            }
        });
        return repo.save(e);
    }

    @Transactional
    public void delete(Long id, UUID orgId) {
        Education e = repo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Education not found"));
        orgGuard.assertExecutiveInOrg(e.getExecutiveId(), orgId);
        repo.deleteById(id);
    }
}
