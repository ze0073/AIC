package com.shijie.accessibility.hosting;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
@Component public class HostingEvents {
    private final JdbcTemplate jdbc;
    private final Map<String,Set<Listener>> listeners=new ConcurrentHashMap<>();
    public HostingEvents(JdbcTemplate jdbc){this.jdbc=jdbc;}
    record Listener(SseEmitter emitter,String user,String project,Supplier<Object> snapshot,long version){}
    public SseEmitter subscribe(String id,Object initial,String user,String project,Supplier<Object> snapshot){
        var e=new SseEmitter(1800000L);var listener=new Listener(e,user,project,snapshot,jdbc.queryForObject("SELECT auth_version FROM app_users WHERE id=?",Long.class,user));
        var group=listeners.computeIfAbsent(id,k->ConcurrentHashMap.newKeySet());group.add(listener);
        Runnable clean=()->{group.remove(listener);if(group.isEmpty())listeners.remove(id,group);};
        e.onCompletion(clean);e.onTimeout(clean);e.onError(x->clean.run());send(listener,initial);return e;
    }
    public void publish(String id,Object data){var all=listeners.get(id);if(all!=null)all.removeIf(e->!send(e,data));}
    @Scheduled(fixedDelay=2000) void refresh(){for(var group:listeners.values())for(var listener:group)try{if(!send(listener,listener.snapshot.get()))group.remove(listener);}catch(Exception e){listener.emitter.complete();group.remove(listener);}}
    private boolean send(Listener listener,Object data){try{
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM project_members m JOIN app_users u ON u.id=m.user_id WHERE m.project_id=? AND m.user_id=? AND u.enabled=TRUE AND u.auth_version=?",Integer.class,listener.project,listener.user,listener.version);
        if(count==null||count==0){listener.emitter.complete();return false;}
        listener.emitter.send(SseEmitter.event().name("deployment").data(data));return true;
    }catch(Exception ex){listener.emitter.complete();return false;}}
}
