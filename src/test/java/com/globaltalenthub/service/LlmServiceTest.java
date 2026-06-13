package com.globaltalenthub.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Fallback + timeout semantics for the LLM facade — pro primary, flash on error/timeout. */
class LlmServiceTest {

    private static final long TIMEOUT_MS = 2000;

    private LlmService service(ChatClient pro, ChatClient flash) {
        return new LlmService(pro, flash, TIMEOUT_MS);
    }

    @Test
    void callWithFallback_primarySucceeds_flashNotCalled() {
        ChatClient pro = Mockito.mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient flash = Mockito.mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(pro.prompt().system(anyString()).user(anyString()).call().content()).thenReturn("pro-answer");

        assertThat(service(pro, flash).callWithFallback("sys", "user")).isEqualTo("pro-answer");
        verify(flash, never()).prompt();
    }

    @Test
    void callWithFallback_primaryThrows_returnsFlashResult() {
        ChatClient pro = Mockito.mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient flash = Mockito.mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(pro.prompt().system(anyString()).user(anyString()).call().content())
            .thenThrow(new RuntimeException("pro down"));
        when(flash.prompt().system(anyString()).user(anyString()).call().content()).thenReturn("flash-answer");

        assertThat(service(pro, flash).callWithFallback("sys", "user")).isEqualTo("flash-answer");
    }

    @Test
    void callWithFallback_primaryHangs_timesOut_andFallsBackToFlash() {
        ChatClient pro = Mockito.mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient flash = Mockito.mock(ChatClient.class, RETURNS_DEEP_STUBS);
        // Pro blocks well past the timeout; the bounded call must abandon it.
        when(pro.prompt().system(anyString()).user(anyString()).call().content()).thenAnswer(inv -> {
            Thread.sleep(10_000);
            return "too-late";
        });
        when(flash.prompt().system(anyString()).user(anyString()).call().content()).thenReturn("flash-answer");

        long start = System.currentTimeMillis();
        String result = service(pro, flash).callWithFallback("sys", "user");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isEqualTo("flash-answer");
        assertThat(elapsed).isLessThan(8000); // returned via timeout, not after the 10s sleep
    }

    @Test
    void classify_usesFlashOnly() {
        ChatClient pro = Mockito.mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient flash = Mockito.mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(flash.prompt().user(anyString()).call().content()).thenReturn("classified");

        assertThat(service(pro, flash).classify("prompt")).isEqualTo("classified");
        verify(pro, never()).prompt();
    }

    @Test
    void classify_timeout_throws() {
        ChatClient pro = Mockito.mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient flash = Mockito.mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(flash.prompt().user(anyString()).call().content()).thenAnswer(inv -> {
            Thread.sleep(10_000);
            return "too-late";
        });

        // Callers (EnrichmentFilterService, BriefSummaryService) catch this and fail open.
        assertThatThrownBy(() -> service(pro, flash).classify("prompt"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("timed out");
    }
}
