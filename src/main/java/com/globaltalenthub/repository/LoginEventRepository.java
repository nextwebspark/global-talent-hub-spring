package com.globaltalenthub.repository;

import com.globaltalenthub.entity.LoginEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoginEventRepository extends JpaRepository<LoginEvent, String> {

    List<LoginEvent> findByUserIdOrderByAtDesc(String userId);
}
