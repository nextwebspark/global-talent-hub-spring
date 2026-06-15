package com.globaltalenthub.repository;

import com.globaltalenthub.entity.LoginEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LoginEventRepository extends JpaRepository<LoginEvent, UUID> {

    List<LoginEvent> findByUserIdOrderByAtDesc(UUID userId);
}
