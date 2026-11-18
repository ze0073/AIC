package com.shijie.accessibility.ai;

import com.shijie.accessibility.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QwenAiProviderTest {
    @Test
    void generatesWholeSourceAndRejectsTruncatedModelOutput() throws Exception {
        var mapper=new ObjectMapper();
        var finish=new AtomicReference<>("stop");
        var sent=new AtomicReference<String>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            sent.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String content=mapper.writeValueAsString(Map.of("content","<html lang=\"zh-CN\">完整文件</html>","summary","补充语言","reason","识别语言","benefit","改善发音","prevention","模板统一设置"));
            byte[] response=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason",finish.get(),"message",Map.of("content",content)))));
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            var config=new AppProperties.Ai(true,"qwen","http://127.0.0.1:"+server.getAddress().getPort(),"test-key","model",5000,0);
            var provider=new QwenAiProvider(new AppProperties(Path.of("build/test-storage"),new AppProperties.Scanner(10,10000,60000),config),mapper);
            var request=new AiProvider.RepairRequest("语言缺失","页面没有语言",Map.of(),"index.html","<html>完整文件</html>");
            assertThat(provider.generateRepair(request).orElseThrow().content()).contains("lang=\"zh-CN\"");
            assertThat(sent.get()).contains("index.html","完整文件").doesNotContain("test-key");
            finish.set("length");
            assertThatThrownBy(()->provider.generateRepair(request)).isInstanceOf(AiProviderException.class);
        } finally {server.stop(0);}
    }
    @Test
    void explanationStopsWaitingWhenTheResponseBodyStalls() throws Exception {
        CountDownLatch release=new CountDownLatch(1);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            exchange.sendResponseHeaders(200,0);
            exchange.getResponseBody().write("{\"choices\":[".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
            exchange.close();
        });
        server.start();
        try{
            var ai=new AppProperties.Ai(true,"qwen","http://127.0.0.1:"+server.getAddress().getPort(),"test-key","test-model",1_000,0);
            var provider=new QwenAiProvider(new AppProperties(Path.of("build/test-storage"),new AppProperties.Scanner(10,10_000,60_000),ai),new ObjectMapper());
            long started=System.nanoTime();
            org.junit.jupiter.api.Assertions.assertThrows(AiProviderException.class,()->provider.explainIssue(new AiProvider.IssueRequest("ZAP","权限候选","保存的证据",Map.of(),List.of())));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(2_500);
        }finally{release.countDown();server.stop(0);}
    }

    @Test
    void parsesStructuredExplanationWithEvidenceAndFixSteps() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ObjectMapper mapper=new ObjectMapper();
        server.createContext("/chat/completions",exchange->{
            String content=mapper.writeValueAsString(Map.of("phenomenon","身份数据混用","possibleCauses",List.of("可能缺少归属检查"),"evidenceBasis",List.of("保存的响应包含其他账号的数据"),"fixSuggestions",List.of("限制数据范围"),"verificationSteps",List.of("两个账号分别复测")));
            byte[] response=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",content)))));
            exchange.sendResponseHeaders(200,response.length);
            exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try{
            var ai=new AppProperties.Ai(true,"qwen","http://127.0.0.1:"+server.getAddress().getPort(),"test-key","test-model",5_000,0);
            var provider=new QwenAiProvider(new AppProperties(Path.of("build/test-storage"),new AppProperties.Scanner(10,10_000,60_000),ai),mapper);
            var result=provider.explainIssue(new AiProvider.IssueRequest("ZAP","权限候选","保存的证据",Map.of(),List.of())).orElseThrow();
            assertThat(result.evidenceBasis()).containsExactly("保存的响应包含其他账号的数据");
            assertThat(result.verificationSteps()).containsExactly("两个账号分别复测");
            assertThat(result.reviewRequired()).isTrue();
        }finally{server.stop(0);}
    }

    @Test
    void sendsOnlyConfiguredRequestAndParsesAdvisoryJson() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"choices":[{"message":{"content":"{\\"summary\\":\\"图片只显示校园图书馆入口\\",\\"confidenceBand\\":\\"medium\\",\\"reasons\\":[\\"截图中可见图书馆建筑\\"],\\"recommendedAction\\":\\"请核对图片是否用于介绍图书馆\\",\\"candidateText\\":\\"校园图书馆入口\\",\\"reviewRequired\\":true}"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var ai = new AppProperties.Ai(true, "qwen",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1",
                    "test-key", "qwen3-vl-plus", 5_000, 0);
            var properties = new AppProperties(Path.of("build/test-storage"),
                    new AppProperties.Scanner(10, 10_000, 60_000), ai);
            var provider = new QwenAiProvider(properties, new ObjectMapper());

            var suggestion = provider.suggest(new AiProvider.Request("R001", "index.html", "#hero",
                    Map.of("outerHtml", "<img src=library.png>"), "data:image/png;base64,AA=="));

            assertThat(suggestion).isPresent();
            assertThat(suggestion.orElseThrow().summary()).contains("图书馆");
            assertThat(suggestion.orElseThrow().confidenceBand()).isEqualTo("medium");
            assertThat(suggestion.orElseThrow().candidateText()).isEqualTo("校园图书馆入口");
            assertThat(suggestion.orElseThrow().reviewRequired()).isTrue();
            assertThat(requestBody.get()).contains("qwen3-vl-plus", "data:image/png;base64,AA==")
                    .doesNotContain("test-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remainsUnavailableUntilTheKeyAndEndpointAreConfigured() {
        var properties = new AppProperties(Path.of("build/test-storage"),
                new AppProperties.Scanner(10, 10_000, 60_000),
                new AppProperties.Ai(true, "qwen", "", "", "qwen3-vl-plus", 5_000, 0));
        var provider = new QwenAiProvider(properties, new ObjectMapper());

        assertThat(provider.enabled()).isFalse();
        assertThat(provider.unavailableReason()).contains("API 地址");
    }

    @Test
    void forcesR009CandidateTextToRemainEmpty() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] response = """
                    {"choices":[{"message":{"content":"{\\"summary\\":\\"顺序需复核\\",\\"confidenceBand\\":\\"high\\",\\"reasons\\":[\\"视觉与 DOM 不同\\"],\\"recommendedAction\\":\\"人工复核\\",\\"candidateText\\":\\"恶意补丁\\"}"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var ai = new AppProperties.Ai(true, "qwen", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-key", "qwen3-vl-plus", 5_000, 0);
            var provider = new QwenAiProvider(new AppProperties(Path.of("build/test-storage"),
                    new AppProperties.Scanner(10, 10_000, 60_000), ai), new ObjectMapper());
            var suggestion = provider.suggest(new AiProvider.Request("R009", "index.html", "html",
                    Map.of("conflicts", java.util.List.of()), "data:image/png;base64,AA==")).orElseThrow();
            assertThat(suggestion.candidateText()).isEmpty();
            assertThat(suggestion.reviewRequired()).isTrue();
        } finally {
            server.stop(0);
        }
    }
}
