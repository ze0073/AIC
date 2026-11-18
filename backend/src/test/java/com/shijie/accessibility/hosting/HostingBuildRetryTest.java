package com.shijie.accessibility.hosting;

import com.shijie.accessibility.config.AppProperties;
import com.shijie.accessibility.service.StorageService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostingBuildRetryTest {
    private DockerHostingRuntime runtime(AtomicInteger builds,int failures,String reason)throws Exception {
        var commands=mock(HostingCommands.class);
        when(commands.run(anyString(),anyList(),any(),any(Duration.class),any(),any())).thenAnswer(invocation->{
            List<String> args=invocation.getArgument(1);
            if(args.contains("buildx")){
                if(builds.incrementAndGet()<=failures)throw new IOException(reason);
                return "built";
            }
            return "{\"sj-build-isolated\":{\"IPAddress\":\"172.19.0.3\"}}";
        });
        var properties=mock(AppProperties.class);when(properties.storageRoot()).thenReturn(Path.of("target/build-retry-qa"));
        return new DockerHostingRuntime(commands,mock(StorageService.class),new ObjectMapper(),properties);
    }
    @Test void recoversAfterAnInterruptedRegistryTokenDownload()throws Exception {
        var attempts=new AtomicInteger();
        runtime(attempts,1,"failed to fetch anonymous token: TLS handshake timeout").buildImage("qa",Path.of("context"),"Dockerfile","test",s->{},()->false);
        assertEquals(2,attempts.get());
    }
    @Test void boundsRetriesWhenTheRegistryRemainsUnavailable()throws Exception {
        var attempts=new AtomicInteger();var runtime=runtime(attempts,10,"failed to fetch anonymous token: EOF");
        assertThrows(IOException.class,()->runtime.buildImage("qa",Path.of("context"),"Dockerfile","test",s->{},()->false));
        assertEquals(3,attempts.get());
    }
    @Test void doesNotRepeatCompilationFailures()throws Exception {
        var attempts=new AtomicInteger();var runtime=runtime(attempts,10,"npm ERR! compilation failed");
        assertThrows(IOException.class,()->runtime.buildImage("qa",Path.of("context"),"Dockerfile","test",s->{},()->false));
        assertEquals(1,attempts.get());
    }
}
