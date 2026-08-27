package com.ai.agent.client.dto;

import java.io.Serializable;

/** Responses API 输入内容块。 */
public class LlmFacadeResponsesContent implements Serializable {

    private static final long serialVersionUID = 1L;

    private String type;
    private String text;
    private String imageUrl;
    private String fileId;
    private String detail;

    public LlmFacadeResponsesContent() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
