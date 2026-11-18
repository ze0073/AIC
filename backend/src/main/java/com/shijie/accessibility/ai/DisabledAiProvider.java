package com.shijie.accessibility.ai;

import java.util.Optional;

public class DisabledAiProvider implements AiProvider {
    @Override public boolean enabled() { return false; }
    @Override public String provider() { return "disabled"; }
    @Override public String model() { return ""; }
    @Override public String unavailableReason() { return "AI 服务未启用"; }
    @Override public Optional<Suggestion> suggest(Request request) { return Optional.empty(); }
}
