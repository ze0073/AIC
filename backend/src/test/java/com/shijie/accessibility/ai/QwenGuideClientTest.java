package com.shijie.accessibility.ai;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class QwenGuideClientTest {
    QwenGuideClient client=new QwenGuideClient(null,new ObjectMapper());
    @Test void acceptsOnlyFiveGoalsAndRealEvidenceReferences(){
        var context=Map.<String,Object>of("evidenceIds",List.of("run"));
        assertThat(client.parse("{\"goal\":\"REPORT\",\"advice\":\"查看报告\",\"evidenceRefs\":[\"run\"]}",context).goal()).isEqualTo("REPORT");
        assertThatThrownBy(()->client.parse("{\"goal\":\"DELETE_SITE\",\"advice\":\"删除\",\"evidenceRefs\":[]}",context)).isInstanceOf(AiProviderException.class);
        assertThatThrownBy(()->client.parse("{\"goal\":\"REPORT\",\"advice\":\"已通过\",\"evidenceRefs\":[\"invented\"]}",context)).hasMessageContaining("不存在");
        assertThatThrownBy(()->client.parse("{\"goal\":\"REPORT\",\"advice\":\"已通过\",\"evidenceRefs\":[]}",context)).hasMessageContaining("真实证据");
    }
    @Test void malformedModelReplyIsRejected(){assertThatThrownBy(()->client.parse("not json",Map.of())).isInstanceOf(RuntimeException.class);}
}
