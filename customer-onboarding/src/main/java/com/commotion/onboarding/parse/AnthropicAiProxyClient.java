package com.commotion.onboarding.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Direct-to-Anthropic implementation for environments without the platform's
 * `ai-worker` gRPC service available (local dev, integration tests).
 *
 * <p>Activate by setting `onboarding.ai.proxy=direct` in application.yml.
 */
@Slf4j
@Component
@Profile("!ai-grpc")
@RequiredArgsConstructor
public class AnthropicAiProxyClient implements AiProxyClient {

    private static final MediaType JSON = MediaType.parse("application/json");
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient();

    @Value("${onboarding.ai.api-key:${ANTHROPIC_API_KEY:}}")
    private String apiKey;

    @Value("${onboarding.ai.base-url:https://api.anthropic.com}")
    private String baseUrl;

    @Override
    public ToolUseResponse toolUse(ToolUseRequest request) {
        Map<String, Object> body = Map.of(
                "model", request.model(),
                "max_tokens", request.maxTokens(),
                "tools", List.of(Map.of(
                        "name", request.toolName(),
                        "description", "Emit a structured record.",
                        "input_schema", request.toolSchema()
                )),
                "tool_choice", Map.of("type", "tool", "name", request.toolName()),
                "messages", request.messages()
        );

        try {
            Request req = new Request.Builder()
                    .url(baseUrl + "/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .post(RequestBody.create(mapper.writeValueAsBytes(body), JSON))
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    throw new IOException("Anthropic API " + resp.code()
                            + ": " + (resp.body() != null ? resp.body().string() : ""));
                }
                Map<?, ?> parsed = mapper.readValue(resp.body().byteStream(), Map.class);
                List<?> content = (List<?>) parsed.get("content");

                for (Object block : content) {
                    Map<?, ?> b = (Map<?, ?>) block;
                    if ("tool_use".equals(b.get("type"))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> input = (Map<String, Object>) b.get("input");
                        return new ToolUseResponse(input, content);
                    }
                }
                throw new IllegalStateException("Anthropic response missing tool_use block");
            }
        } catch (IOException e) {
            throw new RuntimeException("Anthropic call failed", e);
        }
    }
}
