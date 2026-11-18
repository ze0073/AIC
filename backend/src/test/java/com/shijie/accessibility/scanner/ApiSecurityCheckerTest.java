package com.shijie.accessibility.scanner;

import com.shijie.accessibility.online.OnlineTargetService;
import com.shijie.accessibility.online.TargetScopeService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiSecurityCheckerTest {
    @Test void masksCredentialLikeQueryValuesInStoredEvidence(){
        var scope=new TargetScopeService();
        var checker=new ApiSecurityChecker(scope,new ObjectMapper(),new com.shijie.accessibility.online.PinnedHttp(scope));
        assertEquals("http://127.0.0.1/api/search?keyword=test&token=<redacted>",
                checker.redactUrl(java.net.URI.create("http://127.0.0.1/api/search?keyword=test&token=private-value")));
    }
    @Test void loginPageAndExpiredTokenStayInconclusiveButProtectedDataIsFlagged() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange->{
            String path=exchange.getRequestURI().getPath();
            String auth=exchange.getRequestHeaders().getFirst("Authorization");
            int status;String body;String type;
            if("Bearer expired".equals(auth)){status=401;body="{\"error\":\"expired\"}";type="application/json";}
            else if("/admin".equals(path)&&"Bearer normal".equals(auth)){status=200;body="{\"internalNote\":\"secret\"}";type="application/json";}
            else if("/applications/2".equals(path)&&"Bearer normal".equals(auth)){status=200;body="{\"owner\":\"user-b\",\"student_name\":\"李同学\"}";type="application/json";}
            else{status=200;body="<html>login</html>";type="text/html";}
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type",type);exchange.sendResponseHeaders(status,bytes.length);
            try(var stream=exchange.getResponseBody()){stream.write(bytes);}
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort()+"/";
            TargetScopeService scope=new TargetScopeService();
            var preview=scope.previewRegistration(base);
            var target=new OnlineTargetService.TargetView("target","project","demo",base,preview.origin(),preview.addresses(),List.of("/"),false,false,null,LocalDateTime.now(),LocalDateTime.now());
            var checker=new ApiSecurityChecker(scope,new ObjectMapper(),new com.shijie.accessibility.online.PinnedHttp(scope));
            var cases=List.<Map<String,Object>>of(
                    Map.of("name","login","kind","UNAUTHENTICATED_ACCESS","path","/admin","expectedStatuses",List.of(401),"protectedFields",List.of("internalNote")),
                    Map.of("name","expired","kind","ROLE_AUTHORIZATION","path","/admin","credentialId","expired","expectedStatuses",List.of(403),"protectedFields",List.of("internalNote")),
                    Map.of("name","role","kind","ROLE_AUTHORIZATION","path","/admin","credentialId","normal","expectedStatuses",List.of(403),"protectedFields",List.of("internalNote")),
                    Map.of("name","ownership","kind","CROSS_USER_ACCESS","path","/applications/2","credentialId","normal","expectedStatuses",List.of(403),"protectedFields",List.of("owner"),"expectedOwner","user-b"));
            var output=checker.check(target,cases,id->new OnlineTargetService.SecretCredential(id,"target","USER_A","BEARER",id,"http://127.0.0.1:"+server.getAddress().getPort()));
            List<String> keys=output.issues().stream().map(OnlineFrontendScanner.IssueDraft::key).toList();
            assertTrue(keys.contains("AUTH_RESPONSE_UNEXPECTED"));
            assertTrue(keys.contains("CREDENTIAL_EXPIRED"));
            assertTrue(keys.contains("ROLE_AUTHORIZATION"));
            assertTrue(keys.contains("CROSS_USER_ACCESS"));
            assertFalse(keys.contains("UNAUTHENTICATED_ACCESS"));
        } finally {server.stop(0);}
    }
}
