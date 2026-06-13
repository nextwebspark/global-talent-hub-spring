package com.globaltalenthub.service;

import com.globaltalenthub.entity.Executive;
import com.globaltalenthub.entity.Remuneration;
import com.globaltalenthub.repository.ExecutiveRepository;
import com.globaltalenthub.repository.RemunerationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutiveServiceTest {

    @Mock ExecutiveRepository executiveRepo;
    @Mock RemunerationRepository remunerationRepo;
    @Mock OrgGuardService orgGuard;
    @Mock RemunerationParserService remunerationParser;

    @InjectMocks ExecutiveService service;

    private Executive existing() {
        Executive e = new Executive();
        e.setId(3L);
        e.setName("Exec");
        return e;
    }

    @Test
    void updateManual_remunerationNotesChanged_reparsesAndReplaces() {
        when(executiveRepo.findByIdAndOrgId(3L, "org-1")).thenReturn(Optional.of(existing()));
        when(executiveRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(remunerationRepo.findByExecutiveId(3L)).thenReturn(List.of());
        Remuneration parsed = new Remuneration();
        parsed.setExecutiveId(3L);
        when(remunerationParser.parse(any(), any())).thenReturn(Optional.of(parsed));

        service.updateManual(3L, Map.of("remunerationNotes", "Base 500k AED plus bonus"), "org-1");

        verify(remunerationParser).parse(any(), any());
        verify(remunerationRepo).save(parsed);
    }

    @Test
    void updateManual_shortRemunerationNotes_deletesWithoutReparse() {
        when(executiveRepo.findByIdAndOrgId(3L, "org-1")).thenReturn(Optional.of(existing()));
        when(executiveRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(remunerationRepo.findByExecutiveId(3L)).thenReturn(List.of());

        service.updateManual(3L, Map.of("remunerationNotes", "n/a"), "org-1");

        verify(remunerationParser, never()).parse(any(), any());
        verify(remunerationRepo, never()).save(any());
    }

    @Test
    void updateManual_noRemunerationNotesKey_doesNotTouchRemuneration() {
        when(executiveRepo.findByIdAndOrgId(3L, "org-1")).thenReturn(Optional.of(existing()));
        when(executiveRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.updateManual(3L, Map.of("title", "CEO"), "org-1");

        verify(remunerationRepo, never()).findByExecutiveId(anyLong());
        verify(remunerationParser, never()).parse(any(), any());
    }

    @Test
    void create_assertsCompanyInOrg_andStampsOrg() {
        Executive in = new Executive();
        in.setCompanyId(10L);
        when(executiveRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Executive result = service.create(in, "org-1");

        verify(orgGuard).assertCompanyInOrg(10L, "org-1");
        assertThat(result.getOrgId()).isEqualTo("org-1");
    }
}
