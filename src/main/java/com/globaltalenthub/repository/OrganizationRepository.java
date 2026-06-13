package com.globaltalenthub.repository;

import com.globaltalenthub.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, String> {

    Optional<Organization> findBySlug(String slug);
}
