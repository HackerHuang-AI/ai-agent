package com.ai.agent.starter.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * @Description: 环境配置类
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.config
 * @ClassName: EnvironmentConfig
 * @Author: HUANGcong
 * @Date: Created in 2026/5/29
 * @Version: 1.0
 */
@Configuration
public class EnvironmentConfig {

    @Getter
    @Value("${spring.profiles.active}")
    private String activeProfile;

    @Value("${app.environment:unknown}")
    private String appEnvironment;

    public boolean isDev() {
        return "dev".equalsIgnoreCase(activeProfile);
    }

    public boolean isTest() {
        return "test".equalsIgnoreCase(activeProfile);
    }

    public boolean isStag() {
        return "stag".equalsIgnoreCase(activeProfile);
    }

    public boolean isProd() {
        return "prod".equalsIgnoreCase(activeProfile);
    }

    public boolean isNotProd() {
        return !isProd();
    }

    public String getEnvironmentDesc() {
        return String.format("profile=%s, app.environment=%s", activeProfile, appEnvironment);
    }
}

