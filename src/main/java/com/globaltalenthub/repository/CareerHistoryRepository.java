package com.globaltalenthub.repository;

import com.globaltalenthub.entity.CareerHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CareerHistoryRepository extends JpaRepository<CareerHistory, Long> {

    List<CareerHistory> findByExecutiveIdOrderBySortOrderAsc(Long executiveId);

    Optional<CareerHistory> findByIdAndExecutiveId(Long id, Long executiveId);
}
