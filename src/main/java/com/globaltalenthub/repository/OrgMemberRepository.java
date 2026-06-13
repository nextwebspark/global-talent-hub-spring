package com.globaltalenthub.repository;

import com.globaltalenthub.entity.OrgMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrgMemberRepository extends JpaRepository<OrgMember, String> {

    Optional<OrgMember> findByUserId(String userId);

    List<OrgMember> findByOrgId(String orgId);

    long countByOrgIdAndRole(String orgId, String role);
}
