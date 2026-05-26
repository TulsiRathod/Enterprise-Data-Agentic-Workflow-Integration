package com.commotion.onboarding.parse;

import java.util.List;
import java.util.Map;

/**
 * Stand-in for the platform's `ai-proxy-utils` gRPC client.
 *
 * <p>In production this is replaced by the generated client that talks to the
 * `ai-worker` service via gRPC and inherits budget tracking, model routing,
 * prompt-cache reuse, and safety filters.
 */
public interface AiProxyClient {

    record ToolUseRequest(
            String model,
            String toolName,
            Map<String, Object> toolSchema,
            List<Map<String, Object>> messages,
            int maxTokens
    ) {}

    record ToolUseResponse(
            Map<String, Object> toolInput,
            Object rawContent
    ) {}

    ToolUseResponse toolUse(ToolUseRequest request);
}
