package com.globaltalenthub.repository;

import com.globaltalenthub.entity.CompanyNotes;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanyNotesRepository extends JpaRepository<CompanyNotes, Long> {

    Optional<CompanyNotes> findFirstByCompanyIdOrderByCreatedAtDesc(Long companyId);
}
