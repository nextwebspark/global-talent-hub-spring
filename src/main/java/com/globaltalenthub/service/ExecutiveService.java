package com.globaltalenthub.service;

import java.util.UUID;

import com.globaltalenthub.entity.Executive;
import com.globaltalenthub.entity.Remuneration;
import com.globaltalenthub.repository.ExecutiveRepository;
import com.globaltalenthub.repository.RemunerationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Executive CRUD (UI layer) — org-scoped. Manual edits override imported data.
 * Port of routes/registrations/executives.ts (CRUD subset). On PATCH, a changed
 * {@code remunerationNotes} re-parses structured remuneration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutiveService {

    private final ExecutiveRepository executiveRepo;
    private final RemunerationRepository remunerationRepo;
    private final OrgGuardService orgGuard;
    private final RemunerationParserService remunerationParser;

    public List<Executive> getByCompany(Long companyId, UUID orgId) {
        orgGuard.assertCompanyInOrg(companyId, orgId);
        return executiveRepo.findByCompanyIdAndOrgId(companyId, orgId);
    }

    @Transactional
    public Executive create(Executive executive, UUID orgId) {
        orgGuard.assertCompanyInOrg(executive.getCompanyId(), orgId);
        executive.setOrgId(orgId);
        return executiveRepo.save(executive);
    }

    @Transactional
    public Executive updateManual(Long id, Map<String, Object> patch, UUID orgId) {
        Executive e = executiveRepo.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Executive not found"));
        applyPatch(e, patch);
        Executive saved = executiveRepo.save(e);

        // Auto-parse remuneration when the notes field was part of the patch.
        if (patch.containsKey("remunerationNotes")) {
            String text = (String) patch.get("remunerationNotes");
            remunerationRepo.deleteAll(remunerationRepo.findByExecutiveId(id));
            if (text != null && text.trim().length() >= 5) {
                Optional<Remuneration> parsed = remunerationParser.parse(text, id);
                parsed.ifPresent(remunerationRepo::save);
            }
        }
        return saved;
    }

    @Transactional
    public void delete(Long id, UUID orgId) {
        orgGuard.assertExecutiveInOrg(id, orgId);
        executiveRepo.deleteById(id);
    }

    private void applyPatch(Executive e, Map<String, Object> patch) {
        patch.forEach((key, value) -> {
            String v = value == null ? null : value.toString();
            switch (key) {
                case "name" -> e.setName(v);
                case "title" -> e.setTitle(v);
                case "email" -> e.setEmail(v);
                case "phone" -> e.setPhone(v);
                case "linkedin" -> e.setLinkedin(v);
                case "profileUrl" -> e.setProfileUrl(v);
                case "imageUrl" -> e.setImageUrl(v);
                case "gender" -> e.setGender(v);
                case "ethnicity" -> e.setEthnicity(v);
                case "notes" -> e.setNotes(v);
                case "remunerationNotes" -> e.setRemunerationNotes(v);
                case "availability" -> e.setAvailability(v);
                case "level" -> e.setLevel(v);
                default -> { /* ignore unknown keys */ }
            }
        });
    }
}
