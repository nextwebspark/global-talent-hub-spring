package com.globaltalenthub.repository;

import com.globaltalenthub.entity.OrgMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgMemberRepository extends JpaRepository<OrgMember, UUID> {

    Optional<OrgMember> findByUserId(UUID userId);

    List<OrgMember> findByOrgId(UUID orgId);

    long countByOrgIdAndRole(UUID orgId, String role);
}
