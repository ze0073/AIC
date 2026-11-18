package com.shijie.accessibility.hosting;

import com.shijie.accessibility.config.AppProperties;
import com.shijie.accessibility.service.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostingResourceDeletionTest {
    @TempDir Path root;
    @Test void deletesOnlyRecordedResourceNamesAndKeepsSharedInfrastructure() throws Exception {
        String site="A".repeat(26),deployment="B".repeat(26),database="C".repeat(26);
        String d=deployment.toLowerCase(),db=database.toLowerCase();
        HostingCommands commands=mock(HostingCommands.class);
        when(commands.run(anyString(),anyList(),any(Path.class),any(Duration.class),any(),any())).thenAnswer(call->{
            List<String> args=call.getArgument(1);
            if (!args.contains("ls")) return "";
            return switch(args.get(1)) {
                case "container" -> "sj-app-"+d+"\nsj-entry-"+d+"\nsj-db-"+db+"\nshijie-mysql\nsj-build-egress\nsj-app-other-site";
                case "volume" -> "sj-data-"+d+"\nsj-dbdata-"+db+"\nplatform-mysql-data\nsj-data-other-site";
                case "network" -> "sj-net-"+d+"\nsj-net-db-"+db+"\nsj-ingress\nsj-build-isolated";
                case "image" -> "shijie-site:"+d+"\nshijie-site:other-site\nmysql:8.4";
                default -> "";
            };
        });
        var runtime=new DockerHostingRuntime(commands,mock(StorageService.class),new ObjectMapper(),new AppProperties(root,null,null));
        runtime.deleteWebsite(site,List.of(deployment),List.of(database));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> args=ArgumentCaptor.forClass(List.class);
        verify(commands,atLeastOnce()).run(anyString(),args.capture(),any(Path.class),any(Duration.class),any(),any());
        List<List<String>> removals=args.getAllValues().stream().filter(c->c.contains("rm")).toList();
        assertThat(removals).hasSize(8);
        assertThat(removals.stream().map(List::getLast)).allMatch(name->name.contains(d)||name.contains(db));
        assertThat(removals.toString()).doesNotContain("shijie-mysql","sj-ingress","sj-build-egress","sha256:","other-site");
    }
}
