package com.ai.agent.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 显式指定扫描根包：启动类位于 com.ai.agent.starter，默认只扫描该包及子包。
// application、infrastructure 模块的包路径与 starter 平级（非子包），需提升扫描起点至公共父包。
@SpringBootApplication(scanBasePackages = "com.ai.agent")
public class AiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAgentApplication.class, args);
    }

}

