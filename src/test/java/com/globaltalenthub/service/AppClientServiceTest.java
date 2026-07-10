package com.globaltalenthub.service;

import com.globaltalenthub.dto.ClientDto;
import com.globaltalenthub.dto.ClientRef;
import com.globaltalenthub.dto.NewClientInput;
import com.globaltalenthub.entity.AppClient;
import com.globaltalenthub.entity.AppCompany;
import com.globaltalenthub.repository.AppClientRepository;
import com.globaltalenthub.repository.AppCompanyRepository;
import com.globaltalenthub.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static com.globaltalenthub.TestIds.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppClientServiceTest {

    @Mock AppClientRepository clientRepo;
    @Mock AppCompanyRepository companyRepo;
    @InjectMocks AppClientService service;

    private static final AuthenticatedUser USER =
        new AuthenticatedUser(uuid("u-a"), "a@x.com", uuid("org-a"), "admin");
    private static final AuthenticatedUser OTHER =
        new AuthenticatedUser(uuid("u-b"), "b@x.com", uuid("org-b"), "admin");

    private void stubSave() {
        when(clientRepo.save(any(AppClient.class))).thenAnswer(inv -> {
            AppClient c = inv.getArgument(0);
            if (c.getId() == null) c.setId(9001L);
            return c;
        });
    }

    @Test
    void resolveOrCreate_existingClientId_loadsOrgScoped() {
        AppClient c = new AppClient();
        c.setId(9001L); c.setOrgId(uuid("org-a")); c.setName("Al Rabie");
        when(clientRepo.findByIdAndOrgId(9001L, uuid("org-a"))).thenReturn(Optional.of(c));

        AppClient out = service.resolveOrCreate(new ClientRef(9001L, null), USER);

        assertThat(out.getId()).isEqualTo(9001L);
    }

    @Test
    void resolveOrCreate_existingClientId_wrongOrg_throws400() {
        when(clientRepo.findByIdAndOrgId(9001L, uuid("org-b"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveOrCreate(new ClientRef(9001L, null), OTHER))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400")
            .hasMessageContaining("Unknown clientId");
    }

    @Test
    void resolveOrCreate_neitherOrBoth_throws400() {
        assertThatThrownBy(() -> service.resolveOrCreate(new ClientRef(null, null), USER))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThatThrownBy(() -> service.resolveOrCreate(
                new ClientRef(1L, new NewClientInput("x", null, null)), USER))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }

    @Test
    void resolveOrCreate_newClient_nameOnly_unlinked() {
        stubSave();

        AppClient out = service.resolveOrCreate(
            new ClientRef(null, new NewClientInput("Private Family Office", null, null)), USER);

        assertThat(out.getId()).isEqualTo(9001L);
        assertThat(out.getOrgId()).isEqualTo(uuid("org-a"));
        assertThat(out.getLinkedCompanyId()).isNull();
    }

    @Test
    void resolveOrCreate_newClient_blankName_throws400() {
        assertThatThrownBy(() -> service.resolveOrCreate(
                new ClientRef(null, new NewClientInput("  ", null, null)), USER))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400")
            .hasMessageContaining("name is required");
    }

    @Test
    void resolveOrCreate_newClient_linked_validatesCatalog_throws400OnUnknown() {
        when(companyRepo.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.resolveOrCreate(
                new ClientRef(null, new NewClientInput("X", null, 999L)), USER))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400")
            .hasMessageContaining("linkedCompanyId");
    }

    @Test
    void resolveOrCreate_newClient_linked_reusesExistingLinkedClient() {
        AppClient existing = new AppClient();
        existing.setId(9002L); existing.setOrgId(uuid("org-a")); existing.setName("Al Rabie");
        existing.setLinkedCompanyId(501L);
        when(companyRepo.existsById(501L)).thenReturn(true);
        when(clientRepo.findByOrgIdAndLinkedCompanyId(uuid("org-a"), 501L))
            .thenReturn(Optional.of(existing));

        AppClient out = service.resolveOrCreate(
            new ClientRef(null, new NewClientInput("Al Rabie", null, 501L)), USER);

        assertThat(out.getId()).isEqualTo(9002L); // reused, not a new insert
    }

    @Test
    void toDto_inlinesLinkedCompany_whenLinked() {
        AppClient c = new AppClient();
        c.setId(9001L); c.setName("Al Rabie"); c.setLinkedCompanyId(501L);
        AppCompany co = new AppCompany();
        co.setId(501L); co.setName("Al Rabie Saudi Foods Co."); co.setHqCountry("SA");
        when(companyRepo.findById(501L)).thenReturn(Optional.of(co));

        ClientDto dto = service.toDto(c);

        assertThat(dto.linkedCompanyId()).isEqualTo(501L);
        assertThat(dto.linkedCompany()).isNotNull();
        assertThat(dto.linkedCompany().hqCountry()).isEqualTo("SA");
    }

    @Test
    void toDto_nullLinked_whenUnlinked() {
        AppClient c = new AppClient();
        c.setId(9001L); c.setName("Family Office");

        ClientDto dto = service.toDto(c);

        assertThat(dto.linkedCompany()).isNull();
    }
}
