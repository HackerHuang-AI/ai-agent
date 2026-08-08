package com.ai.agent.starter.facade;

import com.ai.agent.client.facade.TestFacade;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * TestFacade Dubbo Provider 实现，用于验证超时等动态治理配置是否生效。
 */
@DubboService(group = "ai-agent", version = "1.0.0")
public class TestFacadeImpl implements TestFacade {

    @Override
    public String hello() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "hello, word";
    }
}

