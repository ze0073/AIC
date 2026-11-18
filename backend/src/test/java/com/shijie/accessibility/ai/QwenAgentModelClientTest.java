package com.shijie.accessibility.ai;

import com.shijie.accessibility.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QwenAgentModelClientTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test void rejectsUnlistedActionsAndModules() throws Exception {
        assertThatThrownBy(()->QwenAgentModelClient.parseDecision(json.readTree("{\"action\":\"DELETE\"}")))
                .isInstanceOf(AiProviderException.class);
        assertThatThrownBy(()->QwenAgentModelClient.parseDecision(json.readTree("{\"action\":\"RUN_MODULE\",\"module\":\"UPLOAD\"}")))
                .isInstanceOf(AiProviderException.class);
        assertThatThrownBy(()->QwenAgentModelClient.parsePlan(json.readTree("{\"modules\":[\"FRONTEND\",\"FRONTEND\"],\"includeFlow\":false}")))
                .isInstanceOf(AiProviderException.class);
    }

    @Test void acceptsApprovedNextAction() throws Exception {
        var decision=QwenAgentModelClient.parseDecision(json.readTree("{\"action\":\"RUN_MODULE\",\"module\":\"SECURITY\",\"reason\":\"检查权限\",\"evidenceStepNos\":[]}"));
        assertThat(decision.module()).isEqualTo("SECURITY");
    }

    @Test void retriesTransientModelFailureButNotAuthenticationFailure() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String response=json.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",
                "{\"action\":\"FINISH\",\"reason\":\"已读取结果\",\"evidenceStepNos\":[]}")))));
        server.createContext("/v1/chat/completions",exchange->{
            int status=calls.incrementAndGet()==1?503:200;
            byte[] body=(status==200?response:"temporarily unavailable").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });
        server.start();
        try {
            var client=client(server,1);
            assertThat(client.decide(Map.of()).action()).isEqualTo("FINISH");
            assertThat(calls.get()).isEqualTo(2);
        } finally {server.stop(0);}

        calls.set(0);
        HttpServer unauthorized=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        unauthorized.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();
            exchange.sendResponseHeaders(401,-1);
            exchange.close();
        });
        unauthorized.start();
        try {
            assertThatThrownBy(()->client(unauthorized,1).decide(Map.of()))
                    .isInstanceOf(AiProviderException.class).hasMessageContaining("HTTP 401");
            assertThat(calls.get()).isEqualTo(1);
        } finally {unauthorized.stop(0);}
    }

    private QwenAgentModelClient client(HttpServer server,int retries) {
        var ai=new AppProperties.Ai(true,"qwen","http://127.0.0.1:"+server.getAddress().getPort()+"/v1",
                "test-key","test-model",1000,retries);
        return new QwenAgentModelClient(new AppProperties(Path.of("."),new AppProperties.Scanner(1,1000,1000),ai),json);
    }
}
