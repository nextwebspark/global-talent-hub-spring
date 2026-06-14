package com.globaltalenthub.service;

import com.globaltalenthub.entity.OrgMember;
import com.globaltalenthub.entity.Organization;
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

import static com.globaltalenthub.TestIds.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock OrganizationRepository organizationRepo;
    @Mock OrgMemberRepository orgMemberRepo;
    @Mock UserProfileRepository profileRepo;

    @InjectMocks AuthService service;

    @Test
    void slugify_normalizes() {
        assertThat(AuthService.slugify("Acme Holdings & Co.")).isEqualTo("acme-holdings-co");
    }

    @Test
    void signupOrg_createsOrgOwnerMemberAndProfile() {
        when(orgMemberRepo.findByUserId(uuid("u1"))).thenReturn(Optional.empty());
        when(organizationRepo.findBySlug(anyString())).thenReturn(Optional.empty());
        when(organizationRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = service.signupOrg(uuid("u1"), "u1@example.com",
            Map.of("org", Map.of("name", "Acme Search"), "name", "Jane Doe"));

        assertThat(result.role()).isEqualTo("owner");
        assertThat(result.org().getName()).isEqualTo("Acme Search");
        assertThat(result.org().getSlug()).isEqualTo("acme-search");
        verify(orgMemberRepo).save(any(OrgMember.class));
        verify(profileRepo).save(any()); // fullName seeded
    }

    @Test
    void signupOrg_missingName_throws400() {
        assertThatThrownBy(() -> service.signupOrg(uuid("u1"), null, Map.of("org", Map.of())))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void signupOrg_userAlreadyHasOrg_throws409() {
        when(orgMemberRepo.findByUserId(uuid("u1"))).thenReturn(Optional.of(new OrgMember()));

        assertThatThrownBy(() -> service.signupOrg(uuid("u1"), null, Map.of("org", Map.of("name", "X"))))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(organizationRepo, never()).save(any());
    }

    @Test
    void signupOrg_slugCollision_appendsSuffix() {
        when(orgMemberRepo.findByUserId(uuid("u1"))).thenReturn(Optional.empty());
        when(organizationRepo.findBySlug("acme")).thenReturn(Optional.of(new Organization()));
        when(organizationRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = service.signupOrg(uuid("u1"), null, Map.of("org", Map.of("name", "Acme")));

        assertThat(result.org().getSlug()).startsWith("acme-");
        assertThat(result.org().getSlug()).isNotEqualTo("acme");
    }

    @Test
    void me_noOrg_returnsNullOrgAndRole() {
        when(orgMemberRepo.findByUserId(uuid("u1"))).thenReturn(Optional.empty());
        when(profileRepo.findById(uuid("u1"))).thenReturn(Optional.empty());

        var ctx = service.me(uuid("u1"), "u1@example.com");

        assertThat(ctx.org()).isNull();
        assertThat(ctx.role()).isNull();
        assertThat(ctx.user().get("email")).isEqualTo("u1@example.com");
    }
}
