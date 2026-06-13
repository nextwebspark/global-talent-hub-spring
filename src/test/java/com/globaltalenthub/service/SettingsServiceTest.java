package com.globaltalenthub.service;

import com.globaltalenthub.entity.OrgMember;
import com.globaltalenthub.repository.LoginEventRepository;
import com.globaltalenthub.repository.OrgMemberRepository;
import com.globaltalenthub.repository.OrganizationRepository;
import com.globaltalenthub.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    @Mock UserProfileRepository profileRepo;
    @Mock OrganizationRepository organizationRepo;
    @Mock OrgMemberRepository orgMemberRepo;
    @Mock LoginEventRepository loginEventRepo;

    @InjectMocks SettingsService service;

    private OrgMember member(String id, String role) {
        OrgMember m = new OrgMember();
        m.setId(id);
        m.setOrgId("org-1");
        m.setRole(role);
        return m;
    }

    @Test
    void updateOrganization_nonAdmin_throws403() {
        assertThatThrownBy(() -> service.updateOrganization("org-1", "member", Map.of("name", "X")))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(organizationRepo, never()).save(any());
    }

    @Test
    void updateMemberRole_invalidRole_throws400() {
        assertThatThrownBy(() -> service.updateMemberRole("m1", "org-1", "admin", "superuser"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateMemberRole_demotingOnlyOwner_throws409() {
        when(orgMemberRepo.findById("m1")).thenReturn(Optional.of(member("m1", "owner")));
        when(orgMemberRepo.countByOrgIdAndRole("org-1", "owner")).thenReturn(1L);

        assertThatThrownBy(() -> service.updateMemberRole("m1", "org-1", "admin", "member"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void updateMemberRole_validDemotion_whenMultipleOwners_succeeds() {
        when(orgMemberRepo.findById("m1")).thenReturn(Optional.of(member("m1", "owner")));
        when(orgMemberRepo.countByOrgIdAndRole("org-1", "owner")).thenReturn(2L);
        when(orgMemberRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        OrgMember updated = service.updateMemberRole("m1", "org-1", "admin", "member");

        assertThat(updated.getRole()).isEqualTo("member");
    }

    @Test
    void deleteMember_removingOnlyOwner_throws409() {
        when(orgMemberRepo.findById("m1")).thenReturn(Optional.of(member("m1", "owner")));
        when(orgMemberRepo.countByOrgIdAndRole("org-1", "owner")).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteMember("m1", "org-1", "owner"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(orgMemberRepo, never()).delete(any());
    }

    @Test
    void memberInOtherOrg_treatedAsNotFound() {
        OrgMember other = member("m1", "admin");
        other.setOrgId("org-2");
        when(orgMemberRepo.findById("m1")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.updateMemberRole("m1", "org-1", "admin", "member"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
