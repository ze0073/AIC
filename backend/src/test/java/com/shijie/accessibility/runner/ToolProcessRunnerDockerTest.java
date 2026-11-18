package com.shijie.accessibility.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="docker.tests",matches="true")
class ToolProcessRunnerDockerTest {
    @TempDir Path temporary;
    private final ToolProcessRunner runner=new ToolProcessRunner();

    @Test void realContainerCrashAndTimeoutKeepLogsWithoutReportingCompletion(){
        String suffix=UUID.randomUUID().toString().substring(0,8);
        var crashed=runner.run("crash-"+suffix,command("crash-"+suffix,"echo started; exit 9"),temporary.resolve("crash"),Duration.ofSeconds(15));
        assertEquals("FAILED",crashed.status());
        assertEquals(9,crashed.exitCode());
        assertTrue(crashed.output().contains("started"));
        var timedOut=runner.run("timeout-"+suffix,command("timeout-"+suffix,"echo started; sleep 15"),temporary.resolve("timeout"),Duration.ofSeconds(3));
        assertEquals("TIMEOUT",timedOut.status());
        assertTrue(timedOut.output().contains("started"));
        var missing=runner.run("missing-"+suffix,List.of("docker","run","--rm","--pull=never","--name","shijie-qa-missing-"+suffix,
                "shijie-not-installed:v061"),temporary.resolve("missing"),Duration.ofSeconds(10));
        assertEquals("FAILED",missing.status());
        assertNotEquals(0,missing.exitCode());
        assertFalse(missing.output().isBlank());
    }

    private List<String> command(String name,String script){
        return List.of("docker","run","--rm","--pull=never","--name","shijie-qa-"+name,
                "--entrypoint","sh","zaproxy/zap-stable:2.16.1","-c",script);
    }
}
