package com.globaltalenthub.repository;

import com.globaltalenthub.entity.Remuneration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RemunerationRepository extends JpaRepository<Remuneration, Long> {

    List<Remuneration> findByExecutiveId(Long executiveId);

    List<Remuneration> findByExecutiveIdIn(List<Long> executiveIds);
}
