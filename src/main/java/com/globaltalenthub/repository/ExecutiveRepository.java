package com.globaltalenthub.repository;

import com.globaltalenthub.entity.Executive;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExecutiveRepository extends JpaRepository<Executive, Long> {

    List<Executive> findByCompanyId(Long companyId);

    List<Executive> findByOrgId(UUID orgId);

    Optional<Executive> findByIdAndOrgId(Long id, UUID orgId);

    List<Executive> findByCompanyIdAndOrgId(Long companyId, UUID orgId);

    Optional<Executive> findByClockworkId(String clockworkId);
}
