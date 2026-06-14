package com.globaltalenthub.service;

import java.util.UUID;

import com.globaltalenthub.entity.CompanyNotes;
import com.globaltalenthub.entity.ExecutiveNotes;
import com.globaltalenthub.repository.CompanyNotesRepository;
import com.globaltalenthub.repository.ExecutiveNotesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executive/company notes — GET returns latest, PUT upserts the latest note.
 * Port of notes.ts.
 */
@Service
@RequiredArgsConstructor
public class NotesService {

    private final ExecutiveNotesRepository executiveNotesRepo;
    private final CompanyNotesRepository companyNotesRepo;
    private final OrgGuardService orgGuard;

    public String getExecutiveNotes(Long executiveId, UUID orgId) {
        orgGuard.assertExecutiveInOrg(executiveId, orgId);
        return executiveNotesRepo.findFirstByExecutiveIdOrderByCreatedAtDesc(executiveId)
            .map(ExecutiveNotes::getContent).orElse("");
    }

    @Transactional
    public String putExecutiveNotes(Long executiveId, String content, UUID orgId) {
        orgGuard.assertExecutiveInOrg(executiveId, orgId);
        ExecutiveNotes note = executiveNotesRepo.findFirstByExecutiveIdOrderByCreatedAtDesc(executiveId)
            .orElseGet(() -> {
                ExecutiveNotes n = new ExecutiveNotes();
                n.setExecutiveId(executiveId);
                return n;
            });
        note.setContent(content);
        executiveNotesRepo.save(note);
        return content;
    }

    public String getCompanyNotes(Long companyId, UUID orgId) {
        orgGuard.assertCompanyInOrg(companyId, orgId);
        return companyNotesRepo.findFirstByCompanyIdOrderByCreatedAtDesc(companyId)
            .map(CompanyNotes::getContent).orElse("");
    }

    @Transactional
    public String putCompanyNotes(Long companyId, String content, UUID orgId) {
        orgGuard.assertCompanyInOrg(companyId, orgId);
        CompanyNotes note = companyNotesRepo.findFirstByCompanyIdOrderByCreatedAtDesc(companyId)
            .orElseGet(() -> {
                CompanyNotes n = new CompanyNotes();
                n.setCompanyId(companyId);
                return n;
            });
        note.setContent(content);
        companyNotesRepo.save(note);
        return content;
    }
}
