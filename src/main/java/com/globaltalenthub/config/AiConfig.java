package com.globaltalenthub.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vertexai.VertexAI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Two named ChatClient beans over the Vertex AI Gemini model — primary
 * {@code gemini-2.5-pro} (configured in application.yml) and a flash bean that
 * overrides the model + pins temperature 0 for the deterministic classifier.
 *
 * <p>Excluded from the {@code test} profile because the Vertex starter requires
 * GCP credentials to build the chat model; unit tests mock {@code LlmClassifier}.
 */
@Configuration
@Profile("!test")
public class AiConfig {

    /**
     * Explicit VertexAI client wired with the service-account credentials.
     *
     * <p>Spring AI 1.0.0-M6's Vertex starter does not apply
     * {@code spring.ai.vertex.ai.gemini.credentials-uri} to the VertexAI it builds —
     * it falls back to Application Default Credentials, which fail locally with
     * UNAUTHENTICATED. Providing this bean makes the chat-model auto-config use our
     * credentials. When credentials-uri is unset (e.g. prod on GCP with workload
     * identity / ADC available), this bean is skipped and the default applies.
     */
    @Bean
    @ConditionalOnProperty("spring.ai.vertex.ai.gemini.credentials-uri")
    public VertexAI vertexAi(
            @Value("${spring.ai.vertex.ai.gemini.project-id}") String projectId,
            @Value("${spring.ai.vertex.ai.gemini.location}") String location,
            @Value("${spring.ai.vertex.ai.gemini.credentials-uri}") Resource credentials) throws IOException {
        try (InputStream in = credentials.getInputStream()) {
            GoogleCredentials creds = GoogleCredentials.fromStream(in)
                .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
            return new VertexAI.Builder()
                .setProjectId(projectId)
                .setLocation(location)
                .setCredentials(creds)
                .build();
        }
    }

    @Bean
    @Primary
    public ChatClient geminiPro(VertexAiGeminiChatModel model) {
        // Model is taken from spring.ai.vertex.ai.gemini.chat.options.model.
        return ChatClient.builder(model).build();
    }

    @Bean("geminiFlash")
    public ChatClient geminiFlash(VertexAiGeminiChatModel model,
                                  @Value("${app.fast-model:gemini-2.5-flash}") String flashModel) {
        VertexAiGeminiChatOptions flashOptions = VertexAiGeminiChatOptions.builder()
            .model(flashModel)
            .temperature(0.0)
            .build();
        return ChatClient.builder(model)
            .defaultOptions(flashOptions)
            .build();
    }
}
