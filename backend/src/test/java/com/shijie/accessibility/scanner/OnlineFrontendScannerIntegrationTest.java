package com.shijie.accessibility.scanner;

import com.shijie.accessibility.config.AppProperties;
import com.shijie.accessibility.online.OnlineTargetService;
import com.shijie.accessibility.online.PinnedHttp;
import com.shijie.accessibility.online.TargetScopeService;
import com.shijie.accessibility.service.StorageService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "playwright.tests", matches = "true")
class OnlineFrontendScannerIntegrationTest {
    @TempDir Path temp;

    @Test
    void agentDoesNotSendPageInitiatedPostRequestsWithoutApprovedInteraction() throws Exception {
        AtomicInteger writes=new AtomicInteger();
        HttpServer site=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        site.createContext("/",exchange->{
            byte[] body="<!doctype html><html lang=zh><body><h1>测试页面</h1><script>fetch('/write',{method:'POST',body:'marker'}).catch(()=>{})</script></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","text/html; charset=utf-8");
            exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });
        site.createContext("/write",exchange->{writes.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});
        site.start();
        try{
            TargetScopeService scope=new TargetScopeService();
            var properties=new AppProperties(temp,new AppProperties.Scanner(10,10_000,60_000),AppProperties.Ai.defaults(false));
            var scanner=new OnlineFrontendScanner(scope,new BrowserScanner(new StorageService(properties),properties),new PinnedHttp(scope));
            String origin="http://127.0.0.1:"+site.getAddress().getPort();
            var target=new OnlineTargetService.TargetView("target","project","local",origin+"/",origin,
                    List.of("127.0.0.1"),List.of("/"),false,false,null,LocalDateTime.now(),LocalDateTime.now());
            var result=scanner.scan(target,List.of("/"),List.of(),()->false,true,false);
            assertThat(writes).hasValue(0);
            assertThat(result.issues()).extracting(OnlineFrontendScanner.IssueDraft::key).contains("AGENT_WRITE_BLOCKED");
        }finally{site.stop(0);}
    }

    @Test
    void blocksEveryOutOfScopeRedirectBeforeTheBrowserContactsItsDestination() throws Exception {
        AtomicInteger externalRequests = new AtomicInteger();
        HttpServer outside = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        outside.createContext("/outside", exchange -> {
            externalRequests.incrementAndGet();
            byte[] body = "outside".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        outside.start();
        AtomicInteger escapedPathRequests = new AtomicInteger();
        ServerSocket httpsWitness=new ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));
        AtomicInteger httpsConnections=new AtomicInteger();
        Thread witness=Thread.ofVirtual().start(()->{
            try(var connection=httpsWitness.accept()){httpsConnections.incrementAndGet();}
            catch(java.io.IOException ignored){}
        });
        HttpServer inside = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        inside.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + outside.getAddress().getPort() + "/outside?token=secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        inside.createContext("/allowed", exchange -> {
            exchange.getResponseHeaders().add("Location", "/page");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        inside.createContext("/upgrade", exchange -> {
            exchange.getResponseHeaders().add("Location", "https://127.0.0.1:" + httpsWitness.getLocalPort() + "/outside?token=secret");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        inside.createContext("/page", exchange -> {
            byte[] body = "<!doctype html><html lang=zh><head><title>测试</title></head><body><h1>站内页面</h1></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        inside.createContext("/narrow/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/outside-path?token=secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        inside.createContext("/outside-path", exchange -> {
            escapedPathRequests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        inside.createContext("/narrow/allowed", exchange -> {
            exchange.getResponseHeaders().add("Location", "/narrow/page");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        inside.createContext("/narrow/page", exchange -> {
            byte[] body = "<!doctype html><html lang=zh><body><h1>范围内页面</h1></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        inside.start();
        try {
            TargetScopeService scope = new TargetScopeService();
            var properties = new AppProperties(temp, new AppProperties.Scanner(10, 10_000, 60_000),
                    AppProperties.Ai.defaults(false));
            var scanner = new OnlineFrontendScanner(scope,
                    new BrowserScanner(new StorageService(properties), properties), new PinnedHttp(scope));
            String origin = "http://127.0.0.1:" + inside.getAddress().getPort();
            var target = new OnlineTargetService.TargetView("target", "project", "local", origin + "/", origin,
                    List.of("127.0.0.1"), List.of("/"), false, false, null,
                    LocalDateTime.now(), LocalDateTime.now());

            var blocked = scanner.scan(target, List.of("/redirect"), () -> false);
            assertThat(externalRequests).hasValue(0);
            assertThat(blocked.issues()).extracting(OnlineFrontendScanner.IssueDraft::key)
                    .contains("PAGE_UNAVAILABLE", "OUT_OF_SCOPE_REQUEST");
            assertThat(blocked.issues().stream().filter(issue -> "OUT_OF_SCOPE_REQUEST".equals(issue.key())).toList())
                    .hasSize(1).allSatisfy(issue -> {
                        assertThat(issue.evidence().get("url")).isEqualTo("http://127.0.0.1:" + outside.getAddress().getPort() + "/outside");
                    });

            var upgrade = scanner.scan(target, List.of("/upgrade"), () -> false);
            assertThat(upgrade.issues()).extracting(OnlineFrontendScanner.IssueDraft::key)
                    .contains("PAGE_UNAVAILABLE", "OUT_OF_SCOPE_REQUEST");
            httpsWitness.close();
            witness.join(1000);
            assertThat(httpsConnections).as("unregistered HTTPS origin must not receive a TLS connection").hasValue(0);

            var allowed = scanner.scan(target, List.of("/allowed"), () -> false);
            assertThat(allowed.issues()).extracting(OnlineFrontendScanner.IssueDraft::key)
                    .doesNotContain("PAGE_UNAVAILABLE", "OUT_OF_SCOPE_REQUEST");
            assertThat(allowed.measurements()).hasSize(3);
            assertThat(externalRequests).hasValue(0);

            var narrow = new OnlineTargetService.TargetView("target", "project", "path-limited", origin + "/narrow/", origin,
                    List.of("127.0.0.1"), List.of("/narrow/"), false, false, null,
                    LocalDateTime.now(), LocalDateTime.now());
            var escaped = scanner.scan(narrow, List.of("/narrow/redirect"), () -> false);
            assertThat(escaped.issues()).extracting(OnlineFrontendScanner.IssueDraft::key)
                    .contains("PAGE_UNAVAILABLE", "OUT_OF_SCOPE_REQUEST");
            assertThat(escapedPathRequests).as("same-origin redirect outside registered path must not be sent").hasValue(0);
            assertThat(escaped.issues().stream().filter(issue -> "OUT_OF_SCOPE_REQUEST".equals(issue.key())).toList())
                    .hasSize(1).allSatisfy(issue -> assertThat(issue.evidence().get("url"))
                            .isEqualTo(origin + "/outside-path"));
            var narrowAllowed = scanner.scan(narrow, List.of("/narrow/allowed"), () -> false);
            assertThat(narrowAllowed.issues()).extracting(OnlineFrontendScanner.IssueDraft::key)
                    .doesNotContain("PAGE_UNAVAILABLE", "OUT_OF_SCOPE_REQUEST");
            assertThat(narrowAllowed.measurements()).hasSize(3);
        } finally {
            httpsWitness.close();
            inside.stop(0);
            outside.stop(0);
        }
    }
}
