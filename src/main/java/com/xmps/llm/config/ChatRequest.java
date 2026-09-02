package com.xmps.llm.config;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Chat Completion 请求
 */
@Getter
@Setter
public class ChatRequest {
    private String model;
    private List<ChatMessage> messages;
    private double temperature = 0.3;
    private int maxTokens = 4096;

    // Jackson 序列化用
    @com.fasterxml.jackson.annotation.JsonProperty("max_tokens")
    public int getMaxTokens() {
        return maxTokens;
    }
}
