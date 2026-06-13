package com.globaltalenthub.service;

import com.globaltalenthub.repository.CareerHistoryRepository;
import com.globaltalenthub.repository.CompanyRepository;
import com.globaltalenthub.repository.ExecutiveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class OrgGuardService {

    private final CompanyRepository companyRepo;
    private final ExecutiveRepository executiveRepo;
    private final CareerHistoryRepository careerHistoryRepo;

    public void assertCompanyInOrg(Long companyId, String orgId) {
        companyRepo.findByIdAndOrgId(companyId, orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));
    }

    public void assertExecutiveInOrg(Long execId, String orgId) {
        executiveRepo.findByIdAndOrgId(execId, orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Executive not found"));
    }

    public void assertCareerHistoryInOrg(Long careerHistoryId, String orgId) {
        var careerHistory = careerHistoryRepo.findById(careerHistoryId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Career history not found"));
        // The owning executive must belong to the caller's org.
        executiveRepo.findByIdAndOrgId(careerHistory.getExecutiveId(), orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Career history not found"));
    }
}
