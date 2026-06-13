package com.globaltalenthub.service.clockwork;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-scope tests for the configuration guard. The HTTP round-trip (auth headers,
 * pagination) is exercised by the live integration test, since no mock-HTTP server
 * dependency is on the classpath.
 */
class ClockworkApiClientTest {

    private ClockworkApiClient client(String key, String secret, String firmKey, String firmSlug) {
        return new ClockworkApiClient(key, secret, firmKey, firmSlug,
            "https://api.clockworkrecruiting.com/v3.0");
    }

    @Test
    void notConfigured_whenAnyCredentialBlank() {
        assertThat(client("", "s", "fk", "fs").isConfigured()).isFalse();
        assertThat(client("k", "", "fk", "fs").isConfigured()).isFalse();
        assertThat(client("k", "s", "", "fs").isConfigured()).isFalse();
        assertThat(client("k", "s", "fk", "").isConfigured()).isFalse();
    }

    @Test
    void configured_whenAllCredentialsPresent() {
        assertThat(client("k", "s", "fk", "fs").isConfigured()).isTrue();
    }

    @Test
    void unconfiguredReads_returnEmpty_withoutHttp() {
        ClockworkApiClient c = client("", "", "", "");
        assertThat(c.getProjects()).isEmpty();
        assertThat(c.getProjectPeople("p1")).isEmpty();
        assertThat(c.getCareerHistory("person1")).isEmpty();
    }
}
