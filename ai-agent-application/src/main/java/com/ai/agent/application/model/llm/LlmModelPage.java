package com.ai.agent.application.model.llm;

import com.ai.agent.application.bo.BasePageOutBO;

import java.util.Collections;
import java.util.List;

/**
 * LLM 模型列表统一分页结果。
 */
public class LlmModelPage extends BasePageOutBO<LlmModelInfo> {

    public static LlmModelPage of(List<LlmModelInfo> models, int pageNo, int pageSize) {
        int effectivePageNo = Math.max(pageNo, 1);
        int effectivePageSize = Math.min(Math.max(pageSize, 1), 100);
        List<LlmModelInfo> all = models == null ? Collections.emptyList() : models;
        long total = all.size();
        long offset = (long) (effectivePageNo - 1) * effectivePageSize;
        int fromIndex = (int) Math.min(offset, all.size());
        int toIndex = Math.min(fromIndex + effectivePageSize, all.size());
        LlmModelPage page = new LlmModelPage();
        page.setPageNo(effectivePageNo);
        page.setPageSize(effectivePageSize);
        page.setTotal(total);
        page.setHasNext(toIndex < total);
        page.setRecords(all.subList(fromIndex, toIndex));
        return page;
    }
}

