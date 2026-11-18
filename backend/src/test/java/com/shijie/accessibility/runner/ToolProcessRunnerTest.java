package com.shijie.accessibility.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ToolProcessRunnerTest {
    @TempDir Path temporary;
    private final String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
    private final String classpath=System.getProperty("java.class.path");

    @Test void unavailableAndCrashNeverReportCompletion(){
        var runner=new ToolProcessRunner();
        assertEquals("UNAVAILABLE",runner.run("missing",List.of("shijie-nonexistent-tool-061"),temporary.resolve("missing"),Duration.ofSeconds(2)).status());
        assertEquals("FAILED",runner.run("crash",List.of(java,"-invalid-option"),temporary.resolve("crash"),Duration.ofSeconds(3)).status());
    }

    @Test void timeoutAndCancellationRetainPartialLog() throws Exception {
        var runner=new ToolProcessRunner();
        List<String> command=List.of(java,"-cp",classpath,ProcessFixture.class.getName());
        var timed=runner.run("timeout",command,temporary.resolve("timeout"),Duration.ofSeconds(2));
        assertEquals("TIMEOUT",timed.status());
        assertTrue(timed.output().contains("started"));
        var running=CompletableFuture.supplyAsync(()->runner.run("cancel",command,temporary.resolve("cancel"),Duration.ofSeconds(20)));
        Thread.sleep(700);
        runner.cancel("cancel");
        var cancelled=running.get(10,TimeUnit.SECONDS);
        assertEquals("CANCELLED",cancelled.status());
        assertTrue(cancelled.output().contains("started"));
    }

    public static class ProcessFixture {
        public static void main(String[]args)throws Exception {System.out.println("started");Thread.sleep(15_000);}
    }
}
