package com.shijie.accessibility.service;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentTaskEvents {
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "PARTIAL", "FAILED", "CANCELLED");
    private final ConcurrentHashMap<String, Set<SseEmitter>> listeners = new ConcurrentHashMap<>();

    public SseEmitter subscribe(AgentTaskService.TaskView current) {
        SseEmitter emitter = new SseEmitter(30L * 60 * 1000);
        listeners.computeIfAbsent(current.id(), key -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(current.id(), emitter));
        emitter.onTimeout(() -> remove(current.id(), emitter));
        emitter.onError(error -> remove(current.id(), emitter));
        send(emitter, current);
        return emitter;
    }

    public void publish(AgentTaskService.TaskView current) {
        Set<SseEmitter> emitters = listeners.get(current.id());
        if (emitters == null) return;
        emitters.removeIf(emitter -> !send(emitter, current));
        if (TERMINAL.contains(current.status())) {
            emitters.forEach(SseEmitter::complete);
            listeners.remove(current.id());
        }
    }

    private boolean send(SseEmitter emitter, AgentTaskService.TaskView current) {
        try {
            emitter.send(SseEmitter.event().name("agent-task").id(current.id() + ":" + current.status() + ":" + current.steps().size()).data(current));
            return true;
        } catch (IOException | IllegalStateException ex) {
            emitter.complete();
            return false;
        }
    }

    private void remove(String id, SseEmitter emitter) {
        Set<SseEmitter> set = listeners.get(id);
        if (set != null) set.remove(emitter);
    }
}
