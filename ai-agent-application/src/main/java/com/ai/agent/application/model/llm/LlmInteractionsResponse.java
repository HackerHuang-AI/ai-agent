package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmInteractionsResponse {
    private String id;
    private String model;
    private String status;
    private String continuationToken;
    private String created;
    private String updated;
    private List<Map<String, Object>> steps;
    private LlmUsage usage;
    private String outputText;
}

