package com.shijie.accessibility.online;

import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.scanner.OnlineSecurityScanner;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.nio.file.Path;
import java.security.KeyStore;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PinnedHttpTest {
    @TempDir Path temporary;

    @Test void doesNotFollowRedirectOutsideRegisteredOrigin() throws Exception {
        AtomicInteger outsideHits=new AtomicInteger();
        HttpServer outside=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        outside.createContext("/api/health",exchange->{outsideHits.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});
        outside.start();
        HttpServer source=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        source.createContext("/api/redirect",exchange->{
            exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+outside.getAddress().getPort()+"/api/health");
            exchange.sendResponseHeaders(302,-1);exchange.close();
        });
        source.start();
        try {
            String base="http://127.0.0.1:"+source.getAddress().getPort()+"/api/";
            TargetScopeService scope=new TargetScopeService();
            var registered=scope.validateRegistration(base,List.of("127.0.0.1"),List.of("/api/"));
            var target=new OnlineTargetService.TargetView("target","project","demo",registered.baseUrl(),registered.origin(),
                    registered.addresses(),registered.pathPrefixes(),false,false,null,LocalDateTime.now(),LocalDateTime.now());
            var response=new PinnedHttp(scope).send(target,java.net.URI.create(base+"redirect"),"GET",Map.of(),null,3);
            assertEquals(302,response.statusCode());
            assertEquals(0,outsideHits.get());
            assertThrows(ApiException.class,()->scope.requireAllowed(target,response.firstHeader("location").orElseThrow()));
        } finally {source.stop(0);outside.stop(0);}
    }

    @Test void rejectsUntrustedHttpsCertificateInsteadOfTreatingItAsPassing() throws Exception {
        Fixture fixture=fixture("IP:127.0.0.1","untrusted");
        try {
            String base="https://127.0.0.1:"+fixture.server().getAddress().getPort()+"/";
            TargetScopeService scope=new TargetScopeService();
            var target=target(scope,base);
            assertThrows(javax.net.ssl.SSLException.class,()->new PinnedHttp(scope).send(target,java.net.URI.create(base),"GET",Map.of(),null,3));
        } finally {fixture.server().stop(0);}
    }

    @Test void trustedCertificateSucceedsOnlyForItsOriginalHostname() throws Exception {
        Fixture matching=fixture("DNS:quality.test","matching");
        try{
            String base="https://quality.test:"+matching.server().getAddress().getPort()+"/";
            TargetScopeService scope=aliasScope();
            var response=new PinnedHttp(scope,matching.trust()).send(target(scope,base),java.net.URI.create(base),"GET",Map.of(),null,3);
            assertEquals(200,response.statusCode());
            assertNotNull(response.sslSession());
            var findings=new OnlineSecurityScanner(scope,new PinnedHttp(scope,matching.trust())).inspect(target(scope,base)).issues();
            assertTrue(findings.stream().anyMatch(issue->"CERTIFICATE_EXPIRING".equals(issue.key())));
            assertTrue(findings.stream().anyMatch(issue->"HSTS_MISSING".equals(issue.key())));
            assertTrue(findings.stream().anyMatch(issue->"MIXED_CONTENT".equals(issue.key())));
        }finally{matching.server().stop(0);}
        Fixture wrongHost=fixture("DNS:elsewhere.invalid","wrong-host");
        try{
            String base="https://quality.test:"+wrongHost.server().getAddress().getPort()+"/";
            TargetScopeService scope=aliasScope();
            assertThrows(javax.net.ssl.SSLException.class,()->new PinnedHttp(scope,wrongHost.trust())
                    .send(target(scope,base),java.net.URI.create(base),"GET",Map.of(),null,3));
        }finally{wrongHost.server().stop(0);}
        Fixture healthy=fixture("DNS:quality.test","healthy",90,true,false);
        try{
            String base="https://quality.test:"+healthy.server().getAddress().getPort()+"/";
            TargetScopeService scope=aliasScope();
            var findings=new OnlineSecurityScanner(scope,new PinnedHttp(scope,healthy.trust())).inspect(target(scope,base)).issues();
            assertFalse(findings.stream().anyMatch(issue->List.of("CERTIFICATE_EXPIRING","HSTS_MISSING","MIXED_CONTENT","TRANSPORT_CHECK_INCONCLUSIVE").contains(issue.key())));
        }finally{healthy.server().stop(0);}
    }

    @Test void httpUpgradeNeedsSeparatelyAuthorizedHttpsOriginBeforeCertificateCanPass() throws Exception {
        Fixture https=fixture("DNS:quality.test","upgrade-destination",90,true,false);
        AtomicInteger httpHits=new AtomicInteger();
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/",exchange->{
            httpHits.incrementAndGet();
            exchange.getResponseHeaders().set("Location","https://quality.test:"+https.server().getAddress().getPort()+"/?token=not-for-evidence");
            exchange.sendResponseHeaders(301,-1);
            exchange.close();
        });
        http.start();
        try {
            TargetScopeService scope=aliasScope();
            PinnedHttp pinned=new PinnedHttp(scope,https.trust());
            String httpBase="http://quality.test:"+http.getAddress().getPort()+"/";
            var httpResult=new OnlineSecurityScanner(scope,pinned).inspect(target(scope,httpBase));
            assertEquals(1,httpHits.get());
            assertEquals(0,https.hits().get(),"unregistered HTTPS origin must not be followed");
            assertTrue(httpResult.issues().stream().anyMatch(issue->"HTTPS_REDIRECT_UNVERIFIED".equals(issue.key())
                    &&"INCONCLUSIVE".equals(issue.confidence())));
            assertFalse(httpResult.evidence().toString().contains("not-for-evidence"));
            String httpsBase="https://quality.test:"+https.server().getAddress().getPort()+"/";
            var httpsResult=new OnlineSecurityScanner(scope,pinned).inspect(target(scope,httpsBase));
            assertEquals(1,https.hits().get());
            assertTrue(httpsResult.evidence().containsKey("certificateExpiresAt"));
            assertFalse(httpsResult.issues().stream().anyMatch(issue->List.of("TRANSPORT_CHECK_INCONCLUSIVE",
                    "CERTIFICATE_EXPIRING","HSTS_MISSING","MIXED_CONTENT","CSP_MISSING").contains(issue.key())));
        } finally {http.stop(0);https.server().stop(0);}
    }

    private OnlineTargetService.TargetView target(TargetScopeService scope,String base){
        var registered=scope.validateRegistration(base,List.of("127.0.0.1"),List.of("/"));
        return new OnlineTargetService.TargetView("target","project","tls",registered.baseUrl(),registered.origin(),
                registered.addresses(),registered.pathPrefixes(),false,false,null,LocalDateTime.now(),LocalDateTime.now());
    }
    private TargetScopeService aliasScope(){
        return new TargetScopeService(){@Override Set<String> resolve(String host){return Set.of("127.0.0.1");}};
    }

    private Fixture fixture(String san,String name) throws Exception {return fixture(san,name,1,false,true);}
    private Fixture fixture(String san,String name,int validityDays,boolean secureHeaders,boolean mixedContent) throws Exception {
        Path keyStorePath=temporary.resolve(name+".p12");
        String executable=System.getProperty("os.name").toLowerCase().contains("win")?"keytool.exe":"keytool";
        String keytool=Path.of(System.getProperty("java.home"),"bin",executable).toString();
        Process generated=new ProcessBuilder(keytool,"-genkeypair","-alias","local","-keyalg","RSA","-keysize","2048",
                "-dname","CN=local-test","-ext","SAN="+san,"-validity",String.valueOf(validityDays),"-storetype","PKCS12",
                "-keystore",keyStorePath.toString(),"-storepass","changeit","-keypass","changeit")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertEquals(0,generated.waitFor());
        KeyStore keys=KeyStore.getInstance("PKCS12");
        try(var input=java.nio.file.Files.newInputStream(keyStorePath)){keys.load(input,"changeit".toCharArray());}
        KeyManagerFactory keyManagers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys,"changeit".toCharArray());
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(keyManagers.getKeyManagers(),null,null);
        TrustManagerFactory trustManagers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(keys);
        SSLContext trust=SSLContext.getInstance("TLS");trust.init(null,trustManagers.getTrustManagers(),null);
        AtomicInteger hits=new AtomicInteger();
        HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/",exchange->{
            hits.incrementAndGet();
            if(secureHeaders){exchange.getResponseHeaders().set("Strict-Transport-Security","max-age=31536000");exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'self'");}
            byte[] body=(mixedContent?"<img src='http://example.invalid/plain'>":"<h1>secure</h1>").getBytes();exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}});
        server.start();
        return new Fixture(server,trust,hits);
    }
    private record Fixture(HttpsServer server,SSLContext trust,AtomicInteger hits){}
}
