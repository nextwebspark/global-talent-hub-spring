package com.globaltalenthub.repository;

import com.globaltalenthub.entity.Executive;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExecutiveRepository extends JpaRepository<Executive, Long> {

    List<Executive> findByCompanyId(Long companyId);

    List<Executive> findByOrgId(String orgId);

    Optional<Executive> findByIdAndOrgId(Long id, String orgId);

    List<Executive> findByCompanyIdAndOrgId(Long companyId, String orgId);

    Optional<Executive> findByClockworkId(String clockworkId);
}
