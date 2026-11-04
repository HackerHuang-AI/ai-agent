package com.ai.agent.application.bo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** Service 分页返回结果。 */
@Getter
@Setter
public class BasePageOutBO<T> {
    /** 当前页记录。 */
    private List<T> records;
    /** 符合筛选条件的总记录数。 */
    private long total;
    /** 当前页码，从 1 开始。 */
    private Integer pageNo;
    /** 每页记录数。 */
    private Integer pageSize;
    /** 总页数，可选，未赋值时不返回。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long totalPages;
    /** 是否有下一页，可选，未赋值时不返回。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean hasNext;
}
