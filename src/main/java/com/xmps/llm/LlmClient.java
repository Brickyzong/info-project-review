package com.xmps.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmps.config.LlmProperties;
import com.xmps.llm.config.ChatMessage;
import com.xmps.llm.config.ChatRequest;
import com.xmps.llm.config.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * 自研轻量 LLM 客户端——Spring RestClient 封装 OpenAI 兼容协议。
 * 开发用 DeepSeek；生产切换政务模型网关仅需改 application.yml 配置。
 */
@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final RestClient restClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public LlmClient(LlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("LLM 客户端初始化完成 — provider={}, baseUrl={}, model={}",
                properties.getProvider(), properties.getBaseUrl(), properties.getModel());
    }

    /**
     * 发送聊天请求，返回模型回复文本。
     *
     * @param systemPrompt 系统提示词（注入审查规则）
     * @param userPrompt   用户提示词（提取的方案内容）
     * @return 模型回复的纯文本
     */
    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, properties.getModel());
    }

    /**
     * 指定模型发送聊天请求。
     */
    public String chat(String systemPrompt, String userPrompt, String model) {
        ChatRequest request = new ChatRequest();
        request.setModel(model);
        request.setMessages(List.of(
                new ChatMessage("system", systemPrompt),
                new ChatMessage("user", userPrompt)
        ));

        try {
            log.debug("LLM 请求 — model={}, systemLength={}, userLength={}",
                    model, systemPrompt.length(), userPrompt.length());

            ChatResponse response = restClient.post()
                    .uri("/chat/completions")
                    .body(request)
                    .retrieve()
                    .body(ChatResponse.class);

            if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
                log.warn("LLM 返回空响应");
                return "";
            }

            ChatMessage msg = response.getChoices().get(0).getMessage();
            String content = msg != null ? msg.getContent() : "";
            log.debug("LLM 响应 — length={}", content != null ? content.length() : 0);
            return content != null ? content : "";

        } catch (Exception e) {
            log.error("LLM 调用失败 — model={}, error={}", model, e.getMessage());
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }
}
