package com.xmps.llm.config;

import lombok.Getter;
import lombok.Setter;

/**
 * 表示 OpenAI 兼容协议的一条聊天消息
 */
@Getter
@Setter
public class ChatMessage {
    private String role;
    private String content;

    public ChatMessage() {}

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
