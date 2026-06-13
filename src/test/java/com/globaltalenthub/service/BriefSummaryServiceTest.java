package com.globaltalenthub.service;

import com.globaltalenthub.service.pipeline.LlmClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BriefSummaryServiceTest {

    @Mock
    LlmClassifier classifier;

    @InjectMocks
    BriefSummaryService service;

    @Test
    void summarize_returnsTrimmedModelOutput() {
        when(classifier.classify(anyString())).thenReturn("  Sector: banking\nGeography: UAE  ");
        assertThat(service.summarize("Confidential JD text")).isEqualTo("Sector: banking\nGeography: UAE");
    }

    @Test
    void summarize_failsOpen_returnsEmptyOnError() {
        when(classifier.classify(anyString())).thenThrow(new RuntimeException("vertex down"));
        assertThat(service.summarize("anything")).isEmpty();
    }

    @Test
    void summarize_blankInput_returnsEmpty_withoutCallingLlm() {
        assertThat(service.summarize("   ")).isEmpty();
        assertThat(service.summarize(null)).isEmpty();
    }
}
