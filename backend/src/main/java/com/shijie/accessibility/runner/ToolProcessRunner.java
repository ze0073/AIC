package com.shijie.accessibility.runner;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@Component
public class ToolProcessRunner {
    private final Map<String, Process> running = new ConcurrentHashMap<>();
    private final Map<String, String> containers = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    public Result run(String runId, List<String> command, Path workDirectory, Duration timeout) {
        return run(runId,command,workDirectory,timeout,()->false);
    }

    public Result run(String runId, List<String> command, Path workDirectory, Duration timeout, BooleanSupplier shouldAbort) {
        Path log = workDirectory.resolve("tool.log");
        try {
            if (cancelled.contains(runId)) return new Result("CANCELLED", -1, "", "工具执行已取消");
            Files.createDirectories(workDirectory);
            int nameFlag = command.indexOf("--name");
            if (nameFlag >= 0 && nameFlag + 1 < command.size()) containers.put(runId, command.get(nameFlag + 1));
            Process process = new ProcessBuilder(command).directory(workDirectory.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            running.put(runId, process);
            if (cancelled.contains(runId)) stopContainer(runId);
            long deadline=System.nanoTime()+timeout.toNanos();
            boolean finished=false;
            while(System.nanoTime()<deadline){
                if(process.waitFor(1,TimeUnit.SECONDS)){finished=true;break;}
                if(cancelled.contains(runId))continue;
                if(shouldAbort.getAsBoolean()){
                    stopContainer(runId);
                    process.destroy();
                    if(!process.waitFor(3,TimeUnit.SECONDS))process.destroyForcibly();
                    return new Result("ABORTED",-1,read(log),"预热后连续两个 10 秒窗口错误率超过 20%");
                }
            }
            if (!finished) {
                stopContainer(runId);
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
                return new Result(cancelled.contains(runId) ? "CANCELLED" : "TIMEOUT", -1, read(log), "工具达到运行时限或已取消");
            }
            int exit = process.exitValue();
            if (cancelled.contains(runId)) return new Result("CANCELLED", exit, read(log), "工具执行已取消");
            return new Result(exit == 0 ? "COMPLETED" : "FAILED", exit, read(log), exit == 0 ? null : "工具进程异常退出");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            stopContainer(runId);
            return new Result("CANCELLED", -1, read(log), "工具执行已取消");
        } catch (IOException ex) {
            return new Result("UNAVAILABLE", -1, read(log), concise(ex));
        } finally {
            running.remove(runId);
            containers.remove(runId);
            cancelled.remove(runId);
        }
    }

    public void cancel(String runId) {
        cancelled.add(runId);
        stopContainer(runId);
        Process process = running.get(runId);
        if (process != null && process.isAlive()) {
            process.destroy();
            try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
        }
    }

    private void stopContainer(String runId) {
        String name = containers.get(runId);
        if (name == null) return;
        try {
            Process stop = new ProcessBuilder("docker", "stop", "--time", "1", name)
                    .redirectErrorStream(true).start();
            if (!stop.waitFor(5, TimeUnit.SECONDS)) stop.destroyForcibly();
        } catch (Exception ignored) { }
    }

    public String imageDigest(String image) {
        try {
            Process process = new ProcessBuilder("docker", "image", "inspect", "--format={{.Id}}", image).redirectErrorStream(true).start();
            if (!process.waitFor(4, TimeUnit.SECONDS) || process.exitValue() != 0) return "not-installed";
            return new String(process.getInputStream().readAllBytes()).strip();
        } catch (Exception ex) { return "unavailable"; }
    }

    private static String read(Path path) {
        try {
            if (!Files.exists(path)) return "";
            String value = Files.readString(path);
            return value.length() <= 12_000 ? value : value.substring(value.length() - 12_000);
        } catch (Exception ignored) { return ""; }
    }
    private static String concise(Exception ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }
    public record Result(String status, int exitCode, String output, String error) {}
}
