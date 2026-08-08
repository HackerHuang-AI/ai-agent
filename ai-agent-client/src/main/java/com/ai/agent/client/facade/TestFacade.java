package com.ai.agent.client.facade;

/**
 * 测试用 Dubbo 接口，用于验证超时等动态治理配置是否生效。
 */
public interface TestFacade {

    /**
     * 睡眠 1000ms 后返回固定字符串。
     */
    String hello();
}

