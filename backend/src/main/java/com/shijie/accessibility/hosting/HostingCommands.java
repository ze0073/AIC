package com.shijie.accessibility.hosting;

import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.util.function.*;

@Component
public class HostingCommands {
    private final Map<String,Process> processes=new ConcurrentHashMap<>();
    public String run(String job,List<String> command,Path cwd,Duration timeout,Consumer<String> log,BooleanSupplier cancelled)throws Exception {
        return run(job,command,cwd,timeout,log,cancelled,Map.of());
    }
    public String run(String job,List<String> command,Path cwd,Duration timeout,Consumer<String> log,BooleanSupplier cancelled,Map<String,String> environment)throws Exception {
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("操作已取消");
        Files.createDirectories(cwd);var builder=new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);builder.environment().putAll(environment);
        var process=builder.start();processes.put(job,process);StringBuffer output=new StringBuffer();
        var reader=Thread.ofVirtual().start(()->{try(var input=new BufferedReader(new InputStreamReader(process.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))){String line;while((line=input.readLine())!=null){String value=line.length()>4000?line.substring(0,4000):line; synchronized(output){output.append(value).append('\n');if(output.length()>24000)output.delete(0,output.length()-24000);}log.accept(value);}}catch(IOException ignored){}});
        long deadline=System.nanoTime()+timeout.toNanos();
        try {
            while(!process.waitFor(250,TimeUnit.MILLISECONDS))if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()||System.nanoTime()>deadline){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();throw new IOException(cancelled.getAsBoolean()?"操作已取消":"执行达到时间上限");}
            reader.join(3000);
            if(cancelled.getAsBoolean())throw new IOException("操作已取消");
            if(process.exitValue()!=0)throw new IOException("执行失败："+output);
            return output.toString().strip();
        }finally{processes.remove(job);if(process.isAlive())process.destroyForcibly();}
    }
    public void cancel(String job){var process=processes.get(job);if(process!=null){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}}
}
