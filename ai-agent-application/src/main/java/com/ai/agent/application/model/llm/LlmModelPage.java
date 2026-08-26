package com.ai.agent.application.model.llm;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * @Description: LLM 模型列表统一分页结果。
 */
@Data
@Builder
public class LlmModelPage {

    private int pageNo;
    private int pageSize;
    private long total;
    private boolean hasNext;
    private List<LlmModelInfo> records;

    public static LlmModelPage of(List<LlmModelInfo> models, int pageNo, int pageSize) {
        int effectivePageNo = Math.max(pageNo, 1);
        int effectivePageSize = Math.min(Math.max(pageSize, 1), 20);
        List<LlmModelInfo> all = models == null ? Collections.emptyList() : models;
        long total = all.size();
        long offset = (long) (effectivePageNo - 1) * effectivePageSize;
        int fromIndex = (int) Math.min(offset, all.size());
        int toIndex = Math.min(fromIndex + effectivePageSize, all.size());
        return LlmModelPage.builder()
                .pageNo(effectivePageNo)
                .pageSize(effectivePageSize)
                .total(total)
                .hasNext(toIndex < total)
                .records(all.subList(fromIndex, toIndex))
                .build();
    }
}

