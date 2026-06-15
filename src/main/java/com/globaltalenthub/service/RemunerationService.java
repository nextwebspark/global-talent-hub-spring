package com.globaltalenthub.service;

import java.util.UUID;

import com.globaltalenthub.entity.Remuneration;
import com.globaltalenthub.repository.RemunerationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Remuneration CRUD for an executive. Port of remuneration.ts. */
@Service
@RequiredArgsConstructor
public class RemunerationService {

    private final RemunerationRepository repo;
    private final OrgGuardService orgGuard;

    public List<Remuneration> byExecutive(Long executiveId, UUID orgId) {
        orgGuard.assertExecutiveInOrg(executiveId, orgId);
        return repo.findByExecutiveId(executiveId);
    }

    @Transactional
    public Remuneration create(Long executiveId, Remuneration entry, UUID orgId) {
        orgGuard.assertExecutiveInOrg(executiveId, orgId);
        entry.setExecutiveId(executiveId);
        return repo.save(entry);
    }

    @Transactional
    public Remuneration update(Long id, Map<String, Object> patch, UUID orgId) {
        Remuneration e = repo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Remuneration not found"));
        orgGuard.assertExecutiveInOrg(e.getExecutiveId(), orgId);
        patch.forEach((k, v) -> {
            switch (k) {
                case "baseSalary" -> e.setBaseSalary(dec(v));
                case "housingAllowance" -> e.setHousingAllowance(dec(v));
                case "transportAllowance" -> e.setTransportAllowance(dec(v));
                case "schoolingAllowance" -> e.setSchoolingAllowance(dec(v));
                case "totalAllowances" -> e.setTotalAllowances(dec(v));
                case "bonus" -> e.setBonus(dec(v));
                case "longTermIncentives" -> e.setLongTermIncentives(dec(v));
                case "currency" -> e.setCurrency(v == null ? null : v.toString());
                case "year" -> e.setYear(v == null ? null : v.toString());
                case "notes" -> e.setNotes(v == null ? null : v.toString());
                default -> { }
            }
        });
        return repo.save(e);
    }

    @Transactional
    public void delete(Long id, UUID orgId) {
        Remuneration e = repo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Remuneration not found"));
        orgGuard.assertExecutiveInOrg(e.getExecutiveId(), orgId);
        repo.deleteById(id);
    }

    private static BigDecimal dec(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
