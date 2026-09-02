package com.xmps.llm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Chat Completion 响应
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatResponse {

    private List<Choice> choices;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        private ChatMessage message;
        private String finishReason;

        @com.fasterxml.jackson.annotation.JsonProperty("finish_reason")
        public String getFinishReason() {
            return finishReason;
        }
    }
}
