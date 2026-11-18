package com.shijie.accessibility.service;

import com.shijie.accessibility.ai.QwenGuideClient;
import com.shijie.accessibility.security.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.*;
import org.springframework.transaction.*;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuideServiceTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);ProjectAccessService access=mock(ProjectAccessService.class);
    GuideWorkflowAdapter adapter=mock(GuideWorkflowAdapter.class);QwenGuideClient qwen=mock(QwenGuideClient.class);
    IdempotencyService keys=mock(IdempotencyService.class);GuideService service;
    String actor="owner",sessionId="session",actionId="action";
    GuideService.Session session=new GuideService.Session(sessionId,"HELP","DELEGATED","RULE",false,"ACTIVE",Map.of(),Map.of(),0,LocalDateTime.now(),List.of(),List.of(),Map.of(),"INTRODUCTION",List.of());
    List<GuideService.Action> actionRows=new ArrayList<>();
    @BeforeEach void setup() {
        var user=mock(AuthService.UserView.class);when(user.id()).thenReturn(actor);when(access.currentUser()).thenReturn(user);
        service=new GuideService(jdbc,new ObjectMapper(),access,adapter,qwen,keys,new AbstractPlatformTransactionManager(){
            protected Object doGetTransaction(){return new Object();}
            protected void doBegin(Object t,TransactionDefinition d){}
            protected void doCommit(DefaultTransactionStatus s){}
            protected void doRollback(DefaultTransactionStatus s){}
        });
        when(jdbc.query(contains("guide_sessions WHERE id=? AND owner_id=?"),any(RowMapper.class),eq(sessionId),eq(actor))).thenReturn(List.of(session));
        when(jdbc.queryForList(contains("guide_sessions WHERE id=? AND owner_id=? FOR UPDATE"),eq(sessionId),eq(actor))).thenReturn(List.of(Map.of("id",sessionId)));
        when(jdbc.query(contains("JOIN guide_sessions s"),any(RowMapper.class),eq(actionId),eq(actor))).thenAnswer(i->List.copyOf(actionRows));
        when(adapter.validateContext(anyMap())).thenAnswer(i->i.getArgument(0));
        when(adapter.resources(anyMap())).thenReturn(Map.of());
        when(keys.find(anyString(),anyString())).thenReturn(Optional.empty());
    }
    GuideService.Action action(String state,LocalDateTime expiry){return new GuideService.Action(actionId,sessionId,"CREATE_SITE",state,Map.of("name","demo"),Map.of(),"hash","guard",Map.of(),null,expiry);}
    @Test void personalSessionReadIsBoundToAuthenticatedOwner(){
        assertThat(service.get(sessionId).id()).isEqualTo(sessionId);
        when(jdbc.query(contains("guide_sessions WHERE id=? AND owner_id=?"),any(RowMapper.class),eq("other-session"),eq(actor))).thenReturn(List.of());
        assertThatThrownBy(()->service.get("other-session")).hasMessageContaining("不存在");
        verify(jdbc).query(contains("owner_id=?"),any(RowMapper.class),eq("other-session"),eq(actor));
    }
    @Test void repeatedConfirmationReturnsReceiptWithoutExecutingAgain() throws Exception {
        actionRows.add(action("PROPOSED",LocalDateTime.now().plusMinutes(25)));when(adapter.guard(anyString(),anyMap())).thenReturn("guard");
        when(adapter.execute(anyString(),anyMap(),anyString(),anyBoolean())).thenReturn(Map.of("operation","CREATE_SITE"));
        service.confirm(actionId,"hash","key",false);
        actionRows.set(0,action("SUCCEEDED",LocalDateTime.now().plusMinutes(25)));
        service.confirm(actionId,"hash","retry-key",false);
        verify(adapter,times(1)).execute(eq("CREATE_SITE"),anyMap(),eq("guide:"+actionId),eq(false));
    }
    @Test void staleResourceCannotStartBusinessWork() throws Exception {
        actionRows.add(action("PROPOSED",LocalDateTime.now().plusMinutes(25)));when(adapter.guard(anyString(),anyMap())).thenReturn("changed");
        assertThatThrownBy(()->service.confirm(actionId,"hash","key",false)).hasMessageContaining("已变化");
        verify(adapter,never()).execute(anyString(),anyMap(),anyString(),anyBoolean());
    }
    @Test void expiredPreviewCannotExecute() throws Exception {
        actionRows.add(action("EXPIRED",LocalDateTime.now().minusSeconds(1)));service.confirm(actionId,"hash","key",false);
        verify(adapter,never()).execute(anyString(),anyMap(),anyString(),anyBoolean());
    }
    @Test void typingConfirmIsOnlyAConversationMessage() throws Exception {
        service.send(sessionId,new GuideService.Send("确认",null,null),"key");
        verify(adapter,never()).execute(anyString(),anyMap(),anyString(),anyBoolean());verify(adapter,never()).prepare(anyString(),anyMap(),anyMap(),anyBoolean());
    }
    @Test void sensitiveInputDoesNotReachModelOrPrepare(){
        service.send(sessionId,new GuideService.Send("Token=private-canary","CREATE_SITE",Map.of("name","demo")),"key");
        verify(adapter,never()).prepare(anyString(),anyMap(),anyMap(),anyBoolean());verifyNoInteractions(qwen);
    }
    @Test void recognisesGoalsAndDerivesStepsFromRealResources(){
        assertThat(GuideService.inferGoal("帮我修复代码")).isEqualTo("REPAIR");
        assertThat(GuideService.step("DEPLOY",Map.of("siteId","site"),Map.of("site",Map.of("sourceVersionId","source")))).isEqualTo("CONFIGURE");
        assertThat(GuideService.step("REPAIR",Map.of("issueId","issue","repairId","repair"),Map.of("repair",Map.of("newVersionId","new")))).isEqualTo("RETEST");
    }
}
