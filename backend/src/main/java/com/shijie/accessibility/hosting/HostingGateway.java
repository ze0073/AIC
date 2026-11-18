package com.shijie.accessibility.hosting;

import com.shijie.accessibility.online.CredentialCipher;
import com.shijie.accessibility.common.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** A separate origin gateway. Only manager-owned loopback upstreams are eligible. */
@Component
public class HostingGateway {
    @Value("${app.hosting.worker-only:false}") private boolean workerOnly;
    private final JdbcTemplate jdbc;private final CredentialCipher cipher;private final HostingTargetRegistry registry;private final int port;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore connections=new Semaphore(256);
    private final Map<String,Ticket> tickets=new ConcurrentHashMap<>(),sessions=new ConcurrentHashMap<>();
    @Value("${app.hosting.gateway-bind-host:127.0.0.1}") private String bindHost="127.0.0.1";
    private ServerSocket server;private volatile String error;
    public HostingGateway(JdbcTemplate jdbc,CredentialCipher cipher,HostingTargetRegistry registry,@Value("${app.hosting.gateway-port:8090}") int port){this.jdbc=jdbc;this.cipher=cipher;this.registry=registry;this.port=port;}
    @PostConstruct void start(){if(workerOnly)return;try{server=new ServerSocket();server.bind(new InetSocketAddress(bindHost,port));executor.submit(()->{while(!server.isClosed()){try{Socket client=server.accept();if(!connections.tryAcquire()){client.close();continue;}executor.submit(()->{try(client){proxy(client);}catch(Exception ignored){}finally{connections.release();}});}catch(IOException e){if(!server.isClosed())error="网站访问入口连接失败";}}});}catch(IOException e){error="网站访问端口 "+port+" 被占用，请修改 APP_HOSTING_GATEWAY_PORT";}}
    @PreDestroy void close(){try{if(server!=null)server.close();}catch(IOException ignored){}executor.shutdownNow();}
    public String error(){return error;}
    public String launch(String site,String deployment,String user,String project){
        long now=System.currentTimeMillis();tickets.entrySet().removeIf(e->e.getValue().expires<now);sessions.entrySet().removeIf(e->e.getValue().expires<now);
        String token=UUID.randomUUID().toString()+UUID.randomUUID();tickets.put(token,new Ticket(deployment,user,project,now+300000,version(user)));
        return registry.url(site,deployment)+"__shijie/access/"+token;
    }
    private void proxy(Socket client)throws Exception {
        client.setSoTimeout(30000);var input=client.getInputStream();var output=client.getOutputStream();
        String first=line(input);if(first==null)return;String[] request=first.split(" ",3);
        if(request.length!=3||!request[0].matches("[A-Z]{3,12}")||!request[1].startsWith("/")||request[1].startsWith("//")||!request[2].equals("HTTP/1.1")){reply(output,400,"无效请求",Map.of());return;}
        var headers=readHeaders(input);String host=headers.getOrDefault("host","").split(":",2)[0].toLowerCase(Locale.ROOT);
        String site,dep;boolean live;
        if(host.matches("d-[0-9a-z]{26}\\.s-[0-9a-z]{26}\\.localhost")){dep=host.substring(2,28).toUpperCase(Locale.ROOT);site=host.substring(31,57).toUpperCase(Locale.ROOT);live=false;}
        else if(host.matches("s-[0-9a-z]{26}\\.localhost")){site=host.substring(2,28).toUpperCase(Locale.ROOT);var current=jdbc.queryForList("SELECT current_deployment_id FROM hosted_sites WHERE id=? AND purpose='HOST' AND deletion_status='ACTIVE'",site);if(current.isEmpty()||current.getFirst().get("current_deployment_id")==null){reply(output,404,"网站尚未发布",Map.of());return;}dep=(String)current.getFirst().get("current_deployment_id");live=true;}
        else{reply(output,404,"网站不存在",Map.of());return;}
        var rows=jdbc.queryForList("SELECT d.upstream_port,d.encrypted_access_token,d.status,d.published FROM hosted_deployments d JOIN hosted_sites s ON s.id=d.site_id WHERE d.id=? AND d.site_id=? AND s.deletion_status='ACTIVE'",dep,site);
        if(rows.isEmpty()){reply(output,404,"网站不存在",Map.of());return;}var row=rows.getFirst();
        if(!Set.of("RUNNING","CHECKING").contains(String.valueOf(row.get("status")))||row.get("upstream_port")==null){reply(output,503,"网站暂未运行，请回到平台查看状态",Map.of());return;}
        if(request[1].startsWith("/__shijie/access/")){
            Ticket ticket=tickets.remove(request[1].substring(17).split("\\?",2)[0]);
            if(ticket==null||!dep.equals(ticket.deployment)||!member(ticket)){reply(output,403,"预览链接已过期，请从网站工作区重新打开",Map.of());return;}
            String token=UUID.randomUUID().toString()+UUID.randomUUID();sessions.put(token,new Ticket(dep,ticket.user,ticket.project,System.currentTimeMillis()+3600000,ticket.version));
            reply(output,302,"",Map.of("Location","/","Set-Cookie","__sj_preview="+token+"; Path=/; HttpOnly; SameSite=Lax; Max-Age=3600"));return;
        }
        boolean publicSite=live&&Boolean.TRUE.equals(row.get("published"));
        String expected=cipher.decrypt((String)row.get("encrypted_access_token"),"deployment-access:"+dep);
        boolean worker=MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),headers.getOrDefault("x-shijie-access","").getBytes(StandardCharsets.UTF_8));
        var ticket=sessions.get(cookie(headers.get("cookie"),"__sj_preview"));
        if(!publicSite&&!worker&&(ticket==null||!dep.equals(ticket.deployment)||!member(ticket))){reply(output,403,"这是私有检测环境，请登录平台后打开网站",Map.of());return;}
        boolean upgrade="websocket".equalsIgnoreCase(headers.get("upgrade"));
        try(Socket upstream=new Socket()){
            try{upstream.connect(new InetSocketAddress("127.0.0.1",((Number)row.get("upstream_port")).intValue()),5000);}catch(IOException unavailable){reply(output,503,"网站运行环境正在恢复，请稍后重试或回到平台查看状态",Map.of("Retry-After","5"));return;}upstream.setSoTimeout(upgrade?0:120000);
            var upstreamOut=upstream.getOutputStream();write(upstreamOut,first+"\r\n");
            for(var header:headers.entrySet()){
                String name=header.getKey();if(Set.of("x-shijie-access","x-forwarded-for","x-forwarded-host","x-forwarded-proto","connection","proxy-connection","keep-alive","proxy-authorization").contains(name))continue;
                String value=header.getValue();if(name.equals("cookie"))value=applicationCookies(value);
                write(upstreamOut,name+": "+value+"\r\n");
            }
            write(upstreamOut,"X-Forwarded-Host: "+host+":"+port+"\r\nX-Forwarded-Proto: http\r\nConnection: "+(upgrade?"Upgrade":"close")+"\r\n\r\n");
            String transfer=headers.getOrDefault("transfer-encoding","");
            if(!transfer.isBlank()){
                if(!transfer.equalsIgnoreCase("chunked")||headers.containsKey("content-length"))throw new IOException("Ambiguous framing");
                long total=0;while(true){String size=line(input);int length=Integer.parseInt(size.split(";",2)[0],16);if(length<0||(total+=length)>20L*1024*1024)throw new IOException("Body too large");write(upstreamOut,size+"\r\n");if(length==0){write(upstreamOut,"\r\n");break;}copy(input,upstreamOut,length);if(!"".equals(line(input)))throw new IOException("Invalid chunk");write(upstreamOut,"\r\n");}
            }else{long length=Long.parseLong(headers.getOrDefault("content-length","0"));if(length<0||length>20L*1024*1024)throw new IOException("Body too large");copy(input,upstreamOut,length);}upstreamOut.flush();
            var upstreamIn=upstream.getInputStream();String status=line(upstreamIn);if(status==null){reply(output,502,"网站暂时没有响应，请回到平台查看运行状态",Map.of());return;}var responseHeaders=readHeaderLines(upstreamIn);write(output,status+"\r\n");
            for(String header:responseHeaders){if(header.toLowerCase(Locale.ROOT).startsWith("set-cookie:"))header=header.replaceAll("(?i);\\s*domain=[^;]*","");if(!header.toLowerCase(Locale.ROOT).startsWith("x-shijie-access:"))write(output,header+"\r\n");}write(output,"\r\n");output.flush();
            if(upgrade&&status.contains(" 101 ")){client.setSoTimeout(0);var pump=executor.submit(()->{try{input.transferTo(upstreamOut);}catch(IOException ignored){}finally{try{upstream.close();}catch(IOException ignored){}}});try{upstreamIn.transferTo(output);}finally{pump.cancel(true);}}
            else upstreamIn.transferTo(output);
        }
    }
    private boolean member(Ticket ticket){if(ticket.expires<System.currentTimeMillis())return false;Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM project_members m JOIN app_users u ON u.id=m.user_id WHERE m.project_id=? AND m.user_id=? AND u.enabled=TRUE AND u.auth_version=?",Integer.class,ticket.project,ticket.user,ticket.version);return count!=null&&count>0;}
    static String applicationCookies(String header){if(header==null)return "";return Arrays.stream(header.split(";")).map(String::strip).filter(part->{String name=part.split("=",2)[0];return !Set.of("__sj_preview","SHIJIE_PLATFORM_SESSION","SHIJIE_PLATFORM_XSRF").contains(name);}).collect(java.util.stream.Collectors.joining("; "));}
    static String cookie(String header,String name){if(header==null)return "";for(String part:header.split(";")){var pair=part.strip().split("=",2);if(pair.length==2&&pair[0].equals(name))return pair[1];}return "";}
    static String line(InputStream in)throws IOException{var bytes=new ByteArrayOutputStream();int c;while((c=in.read())!=-1){if(c=='\n'){var text=bytes.toString(StandardCharsets.ISO_8859_1);if(!text.endsWith("\r"))throw new IOException("Invalid line");return text.substring(0,text.length()-1);}bytes.write(c);if(bytes.size()>8192)throw new IOException("Header too long");}return bytes.size()==0?null:bytes.toString(StandardCharsets.ISO_8859_1);}
    static List<String> readHeaderLines(InputStream in)throws IOException{var result=new ArrayList<String>();String line;int total=0;while((line=line(in))!=null&&!line.isEmpty()){if((total+=line.length())>32768)throw new IOException("Headers too long");if(!line.matches("[A-Za-z0-9-]+:.*"))throw new IOException("Invalid header");result.add(line);}return result;}
    static Map<String,String> readHeaders(InputStream in)throws IOException{Map<String,String> result=new LinkedHashMap<>();for(String header:readHeaderLines(in)){String[] parts=header.split(":",2);String name=parts[0].toLowerCase(Locale.ROOT),value=parts[1].strip();if(result.containsKey(name)&&!name.equals("cookie"))throw new IOException("Duplicate header");result.merge(name,value,(a,b)->a+"; "+b);}return result;}
    static void copy(InputStream in,OutputStream out,long count)throws IOException{byte[] bytes=new byte[8192];while(count>0){int read=in.read(bytes,0,(int)Math.min(count,bytes.length));if(read<0)throw new EOFException();out.write(bytes,0,read);count-=read;}}
    static void write(OutputStream out,String value)throws IOException{out.write(value.getBytes(StandardCharsets.ISO_8859_1));}
    static void reply(OutputStream out,int status,String body,Map<String,String> headers)throws IOException{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);write(out,"HTTP/1.1 "+status+" Response\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n");for(var h:headers.entrySet())write(out,h.getKey()+": "+h.getValue()+"\r\n");write(out,"\r\n");out.write(bytes);}
    private long version(String user){Long value=jdbc.queryForObject("SELECT auth_version FROM app_users WHERE id=?",Long.class,user);return value==null?-1:value;}
    record Ticket(String deployment,String user,String project,long expires,long version){}
}
