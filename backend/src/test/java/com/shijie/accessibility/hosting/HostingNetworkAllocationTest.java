package com.shijie.accessibility.hosting;

import com.shijie.accessibility.config.AppProperties;
import com.shijie.accessibility.service.StorageService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.function.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class HostingNetworkAllocationTest {
    @Test void allocatesSmallNetworkAndRetriesOnlySubnetCollision()throws Exception{
        var properties=mock(AppProperties.class);when(properties.storageRoot()).thenReturn(Path.of("target/network-qa"));
        var commands=mock(HostingCommands.class);var calls=new java.util.ArrayList<List<String>>();
        when(commands.run(anyString(),anyList(),any(),any(Duration.class),any(),any())).thenAnswer(invocation->{
            List<String> args=invocation.getArgument(1);calls.add(args);
            if(args.contains("inspect"))throw new IOException("not found");
            if(calls.size()==2)throw new IOException("Pool overlaps with other one");return "created";
        });
        var runtime=new DockerHostingRuntime(commands,mock(StorageService.class),new ObjectMapper(),properties);
        assertEquals("sj-net-qa",runtime.ensureNetwork("qa","sj-net-qa"));
        assertEquals(3,calls.size());var first=calls.get(1);var second=calls.get(2);
        assertTrue(first.contains("--internal"));assertTrue(first.get(first.indexOf("--subnet")+1).endsWith("/28"));
        assertNotEquals(first.get(first.indexOf("--subnet")+1),second.get(second.indexOf("--subnet")+1));
    }
}
