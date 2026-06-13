package com.globaltalenthub.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

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
