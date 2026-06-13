package com.globaltalenthub.repository;

import com.globaltalenthub.entity.ExecutiveNotes;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExecutiveNotesRepository extends JpaRepository<ExecutiveNotes, Long> {

    Optional<ExecutiveNotes> findFirstByExecutiveIdOrderByCreatedAtDesc(Long executiveId);
}
