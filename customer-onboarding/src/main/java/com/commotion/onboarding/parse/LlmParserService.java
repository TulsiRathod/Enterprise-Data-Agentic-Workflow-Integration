package com.commotion.onboarding.parse;

import com.commotion.onboarding.schema.CustomerDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubspot.jinjava.Jinjava;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmParserService {

    private final AiProxyClient aiProxy;
    private final Jinjava jinjava;
    private final Validator validator;
    private final ObjectMapper mapper;

    @Value("${onboarding.parse.max-repairs:2}")
    private int maxRepairs;

    @Value("${onboarding.parse.model:claude-opus-4-7}")
    private String model;

    private static final String PROMPT_TEMPLATE = """
        Extract the customer record from the document below and call
        `emit_customer` with the structured result. Omit any field whose
        value is not present in the document.

        <doc>
        {{ document }}
        </doc>
        """;

    private static final Map<String, Object> CUSTOMER_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "externalId", Map.of("type", "string"),
                    "fullName",   Map.of("type", "string"),
                    "email",      Map.of("type", "string", "format", "email"),
                    "company",    Map.of("type", "string"),
                    "country",    Map.of("type", "string"),
                    "notes",      Map.of("type", "string")),
            "required", List.of("externalId", "fullName", "email", "country")
    );

    public CustomerDto parse(String docText) {
        String prompt = jinjava.render(PROMPT_TEMPLATE, Map.of("document", docText));

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", prompt));

        for (int attempt = 0; attempt <= maxRepairs; attempt++) {
            AiProxyClient.ToolUseResponse resp = aiProxy.toolUse(
                    new AiProxyClient.ToolUseRequest(
                            model, "emit_customer", CUSTOMER_SCHEMA, messages, 2048));

            CustomerDto candidate = mapper.convertValue(resp.toolInput(), CustomerDto.class);

            Set<ConstraintViolation<CustomerDto>> violations = validator.validate(candidate);
            if (violations.isEmpty()) {
                return candidate;
            }

            if (attempt == maxRepairs) {
                log.warn("LLM parse failed after {} repairs: {}", maxRepairs, violations);
                throw new ParseException("validation failed: " + violations);
            }

            messages.add(message("assistant", resp.rawContent()));
            messages.add(message("user",
                    "Validation failed: " + violations
                            + ". Fix the listed fields and re-emit via emit_customer."));
        }
        throw new IllegalStateException("unreachable");
    }

    private static Map<String, Object> message(String role, Object content) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    public static class ParseException extends RuntimeException {
        public ParseException(String message) { super(message); }
    }
}
