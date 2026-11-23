package com.fitnesscoaching.platform.modules.ai.application.port.out;

/** F06 recognition only; Nutrition owns catalog matching and confirmed logs. */
public interface F06AiServiceClient {
    String estimateTextJson(String text);
}
