package com.shijie.accessibility.online;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One scoped request per connection. DNS never selects an address outside the registered target. */
@Component
public class PinnedHttp {
    private final TargetScopeService scope;
    private final SSLContext testTlsContext;
    @Autowired public PinnedHttp(TargetScopeService scope){this(scope,null);}
    PinnedHttp(TargetScopeService scope,SSLContext testTlsContext){this.scope=scope;this.testTlsContext=testTlsContext;}

    public Result send(OnlineTargetService.TargetView target,URI uri,String method,Map<String,String> headers,byte[] body,int timeoutSeconds) throws Exception {
        scope.requireAllowed(target,uri.toString());
        String host=URI.create(target.baseUrl()).getHost();
        String address=scope.pinnedAddress(target);
        DnsResolver resolver=new DnsResolver(){
            @Override public InetAddress[] resolve(String candidate)throws UnknownHostException {
                if(!host.equalsIgnoreCase(candidate))throw new UnknownHostException("目标主机超出固定地址范围");
                return new InetAddress[]{InetAddress.getByName(address)};
            }
            @Override public String resolveCanonicalHostname(String candidate)throws UnknownHostException {
                if(!host.equalsIgnoreCase(candidate))throw new UnknownHostException("目标主机超出固定地址范围");
                return host;
            }
        };
        var managerBuilder=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver);
        if(testTlsContext!=null)managerBuilder.setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                .setSslContext(testTlsContext).setHostnameVerifier(new DefaultHostnameVerifier()).build());
        var manager=managerBuilder.build();
        var config=RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(timeoutSeconds)).build();
        try(var client=HttpClients.custom().setConnectionManager(manager).setDefaultRequestConfig(config)
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().build()) {
            HttpUriRequestBase request=new HttpUriRequestBase(method,uri);
            headers.forEach(request::setHeader);
            scope.requestHeaders(target).forEach(request::setHeader);
            if(body!=null)request.setEntity(new ByteArrayEntity(body,ContentType.APPLICATION_OCTET_STREAM));
            HttpClientContext context=HttpClientContext.create();
            Result result=client.execute(request,context,response->{
                Map<String,List<String>> responseHeaders=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for(Header header:response.getHeaders())responseHeaders.computeIfAbsent(header.getName(),unused->new ArrayList<>()).add(header.getValue());
                byte[] bytes=response.getEntity()==null?new byte[0]:EntityUtils.toByteArray(response.getEntity());
                return new Result(response.getCode(),responseHeaders,new String(bytes,StandardCharsets.UTF_8),null,bytes);
            });
            return new Result(result.statusCode(),result.headers(),result.body(),context.getSSLSession(),result.bodyBytes());
        }
    }
    public record Result(int statusCode,Map<String,List<String>> headers,String body,SSLSession sslSession,byte[] bodyBytes){
        public Result(int statusCode,Map<String,List<String>> headers,String body,SSLSession sslSession){
            this(statusCode,headers,body,sslSession,body==null?new byte[0]:body.getBytes(StandardCharsets.UTF_8));
        }
        public Optional<String> firstHeader(String name){return headers.getOrDefault(name,List.of()).stream().findFirst();}
        public List<String> allHeaders(String name){return headers.getOrDefault(name,List.of());}
    }
}
