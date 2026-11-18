package com.shijie.accessibility.hosting;

import com.shijie.accessibility.config.AppProperties;
import com.shijie.accessibility.service.StorageService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostingLocalImageTest {
    private DockerHostingRuntime runtime(HostingCommands commands) {
        var properties=mock(AppProperties.class);
        when(properties.storageRoot()).thenReturn(Path.of("target/image-qa"));
        return new DockerHostingRuntime(commands,mock(StorageService.class),new ObjectMapper(),properties);
    }

    @Test void cachedHelperWorksWhenRegistryIsUnavailable()throws Exception {
        var commands=mock(HostingCommands.class);
        var calls=new ArrayList<List<String>>();
        when(commands.run(anyString(),anyList(),any(),any(Duration.class),any(),any())).thenAnswer(invocation->{
            List<String> args=invocation.getArgument(1);calls.add(args);
            if(args.contains("pull"))throw new IOException("TLS handshake timeout");
            return "sha256:cached";
        });
        assertDoesNotThrow(()->runtime(commands).ensureLocalImage("qa","busybox:1.37",s->{},()->false));
        assertEquals(1,calls.size());
        assertTrue(calls.getFirst().contains("inspect"));
    }

    @Test void missingHelperIsDownloadedAndPullFailurePropagates()throws Exception {
        var commands=mock(HostingCommands.class);
        var calls=new ArrayList<List<String>>();
        when(commands.run(anyString(),anyList(),any(),any(Duration.class),any(),any())).thenAnswer(invocation->{
            List<String> args=invocation.getArgument(1);calls.add(args);
            throw new IOException(args.contains("pull")?"TLS handshake timeout":"No such image");
        });
        var error=assertThrows(IOException.class,()->runtime(commands).ensureLocalImage("qa","busybox:1.37",s->{},()->false));
        assertEquals("TLS handshake timeout",error.getMessage());
        assertEquals(List.of("docker","pull","busybox:1.37"),calls.getLast());
    }
}
