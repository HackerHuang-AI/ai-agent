package com.ai.agent.starter.config;

import com.ai.agent.infrastructure.utils.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class JacksonConfig {

    private final ObjectMapper objectMapper;

    @PostConstruct
    public void initJsonUtils() {
        JsonUtil.init(objectMapper);
    }
}

