package com.shijie.accessibility.service;

import com.shijie.accessibility.model.ScanTaskEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ScanEvents {
    private final ConcurrentHashMap<String, Set<SseEmitter>> listeners = new ConcurrentHashMap<>();

    public SseEmitter subscribe(ScanTaskEntity current) {
        SseEmitter emitter = new SseEmitter(30L * 60 * 1000);
        listeners.computeIfAbsent(current.id, key -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(current.id, emitter));
        emitter.onTimeout(() -> remove(current.id, emitter));
        emitter.onError(error -> remove(current.id, emitter));
        sendOne(emitter, current);
        return emitter;
    }

    public void publish(ScanTaskEntity scan) {
        Set<SseEmitter> emitters = listeners.get(scan.id);
        if (emitters == null) return;
        emitters.removeIf(emitter -> !sendOne(emitter, scan));
        if (Set.of("COMPLETED", "PARTIAL", "FAILED", "CANCELLED").contains(scan.status)) {
            emitters.forEach(SseEmitter::complete);
            listeners.remove(scan.id);
        }
    }

    private boolean sendOne(SseEmitter emitter, ScanTaskEntity scan) {
        try {
            emitter.send(SseEmitter.event().name("scan-progress").id(scan.id + ":" + scan.progress).data(scan));
            return true;
        } catch (IOException | IllegalStateException ex) {
            emitter.complete();
            return false;
        }
    }

    private void remove(String scanId, SseEmitter emitter) {
        Set<SseEmitter> emitters = listeners.get(scanId);
        if (emitters != null) emitters.remove(emitter);
    }
}

