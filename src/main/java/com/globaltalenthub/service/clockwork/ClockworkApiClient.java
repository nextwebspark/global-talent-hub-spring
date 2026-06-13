package com.globaltalenthub.service.clockwork;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Clockwork Recruiting REST client. Port of clockworkEnrichment/apiClient + projects +
 * people. Auth: {@code Authorization: Token base64(apiKey:apiSecret)} + {@code X-API-Key: firmKey},
 * base {@code {baseUrl}/{firmSlug}/...}. All calls are guarded by {@link #isConfigured()}.
 */
@Service
@Slf4j
public class ClockworkApiClient {

    private final String firmSlug;
    private final String firmKey;
    private final String authToken;
    private final boolean configured;
    private final WebClient webClient;

    public ClockworkApiClient(
            @Value("${CLOCKWORK_API_KEY:}") String apiKey,
            @Value("${CLOCKWORK_API_SECRET:}") String apiSecret,
            @Value("${CLOCKWORK_FIRM_KEY:}") String firmKey,
            @Value("${CLOCKWORK_FIRM_SLUG:}") String firmSlug,
            @Value("${CLOCKWORK_API_URL:https://api.clockworkrecruiting.com/v3.0}") String baseUrl) {
        this.firmSlug = firmSlug;
        this.firmKey = firmKey;
        this.configured = notBlank(apiKey) && notBlank(apiSecret) && notBlank(firmKey) && notBlank(firmSlug);
        this.authToken = configured
            ? Base64.getEncoder().encodeToString((apiKey + ":" + apiSecret).getBytes(StandardCharsets.UTF_8))
            : "";
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    public boolean isConfigured() {
        return configured;
    }

    /** GET {firmSlug}/projects → list of project nodes. */
    public List<JsonNode> getProjects() {
        JsonNode root = get("projects");
        List<JsonNode> out = new ArrayList<>();
        if (root != null) {
            JsonNode arr = root.has("projects") ? root.get("projects") : root;
            if (arr.isArray()) arr.forEach(out::add);
        }
        return out;
    }

    /** GET {firmSlug}/projects/{projectId}/assignments — paginate while a next page exists. */
    public List<JsonNode> getProjectPeople(String projectId) {
        List<JsonNode> people = new ArrayList<>();
        int page = 1;
        while (true) {
            JsonNode root = get("projects/" + projectId + "/assignments?page=" + page);
            if (root == null) break;
            JsonNode arr = root.has("assignments") ? root.get("assignments")
                : root.has("people") ? root.get("people") : root;
            if (!arr.isArray() || arr.isEmpty()) break;
            arr.forEach(people::add);
            // Stop when fewer than a full page is returned, or no next-page marker.
            JsonNode next = root.get("nextPage");
            if (next == null || next.isNull()) break;
            page++;
            if (page > 100) break; // hard safety cap
        }
        return people;
    }

    /** GET {firmSlug}/people/{personId}/positions — career history. */
    public List<JsonNode> getCareerHistory(String personId) {
        JsonNode root = get("people/" + personId + "/positions");
        List<JsonNode> out = new ArrayList<>();
        if (root != null) {
            JsonNode arr = root.has("positions") ? root.get("positions") : root;
            if (arr.isArray()) arr.forEach(out::add);
        }
        return out;
    }

    private JsonNode get(String endpoint) {
        if (!configured) return null;
        try {
            return webClient.get()
                .uri("/{slug}/{endpoint}", firmSlug, endpoint)
                .header("Authorization", "Token " + authToken)
                .header("X-API-Key", firmKey)
                .header("Accept", "application/json")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        } catch (Exception e) {
            log.warn("[Clockwork] GET /{}/{} failed: {}", firmSlug, endpoint, e.getMessage());
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
