package com.shijie.accessibility.service;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RunEvents {
    private final ConcurrentHashMap<SseEmitter,java.util.function.BooleanSupplier> permissions=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,java.util.function.Supplier<TestRunService.RunView>> refreshers=new ConcurrentHashMap<>();
    public void refreshFromDatabase(String id,java.util.function.Supplier<TestRunService.RunView> supplier){refreshers.put(id,supplier);}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=2000) void refresh(){for(var entry:refreshers.entrySet()){if(!listeners.containsKey(entry.getKey())){refreshers.remove(entry.getKey());continue;}try{publish(entry.getValue().get());}catch(Exception denied){var group=listeners.remove(entry.getKey());if(group!=null)group.forEach(SseEmitter::complete);refreshers.remove(entry.getKey());}}}
    private final ConcurrentHashMap<String, Set<SseEmitter>> listeners = new ConcurrentHashMap<>();

    public SseEmitter subscribe(TestRunService.RunView current,java.util.function.BooleanSupplier permitted) {
        SseEmitter emitter = new SseEmitter(30L * 60 * 1000);
        permissions.put(emitter,permitted);
        listeners.computeIfAbsent(current.id(), key -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(current.id(), emitter));
        emitter.onTimeout(() -> remove(current.id(), emitter));
        emitter.onError(error -> remove(current.id(), emitter));
        send(emitter, current);
        return emitter;
    }

    public void publish(TestRunService.RunView current) {
        Set<SseEmitter> emitters = listeners.get(current.id());
        if (emitters == null) return;
        emitters.removeIf(emitter -> {try {if(!permissions.getOrDefault(emitter,()->false).getAsBoolean()){emitter.complete();permissions.remove(emitter);return true;}}catch(Exception denied){emitter.complete();permissions.remove(emitter);return true;}boolean sent=send(emitter,current);if(!sent)permissions.remove(emitter);return !sent;});
        if (Set.of("COMPLETED", "PARTIAL", "FAILED", "CANCELLED").contains(current.status())) {
            emitters.forEach(SseEmitter::complete);
            emitters.forEach(permissions::remove);
            listeners.remove(current.id());
            refreshers.remove(current.id());
        }
    }

    private boolean send(SseEmitter emitter, TestRunService.RunView current) {
        try { emitter.send(SseEmitter.event().name("test-run").id(current.id() + ":" + current.status()).data(current)); return true; }
        catch (IOException | IllegalStateException ex) { emitter.complete(); return false; }
    }
    private void remove(String id, SseEmitter emitter) { permissions.remove(emitter);Set<SseEmitter> set=listeners.get(id); if(set!=null)set.remove(emitter); }
}
