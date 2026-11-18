package com.shijie.accessibility.service;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

@Component
public class StreamRevocations {
    private final ConcurrentHashMap<SseEmitter,BooleanSupplier> streams=new ConcurrentHashMap<>();
    public SseEmitter watch(SseEmitter stream,BooleanSupplier allowed){
        streams.put(stream,allowed);Runnable remove=()->streams.remove(stream);
        stream.onCompletion(remove);stream.onTimeout(remove);stream.onError(ex->remove.run());return stream;
    }
    @Scheduled(fixedDelay=1000) void check(){streams.forEach((stream,allowed)->{try{if(allowed.getAsBoolean())return;}catch(Exception ignored){}streams.remove(stream);stream.complete();});}
}
