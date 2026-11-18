package com.shijie.accessibility.hosting;

import com.shijie.accessibility.service.StorageService;
import com.shijie.accessibility.online.CredentialCipher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.function.*;

@Component
public class DockerHostingRuntime {
    @org.springframework.beans.factory.annotation.Value("${app.hosting.network-prefix:10.253}") private String networkPrefix="10.253";
    private final HostingCommands commands;private final StorageService storage;private final ObjectMapper json;private final Path root;
    public DockerHostingRuntime(HostingCommands commands,StorageService storage,ObjectMapper json,com.shijie.accessibility.config.AppProperties properties){this.commands=commands;this.storage=storage;this.json=json;this.root=properties.storageRoot().toAbsolutePath().normalize().resolve("hosting");}
    public Path directory(String id){if(!id.matches("[a-zA-Z0-9_-]{1,80}"))throw new IllegalArgumentException("无效任务路径");return root.resolve(id);}
    String command(String job,List<String> args,Duration timeout,Consumer<String> log,BooleanSupplier cancelled)throws Exception{return commands.run(job,args,directory(job),timeout,log,cancelled);}
    public Map<String,Object> environment(){
        try{String[] info=command("environment",List.of("docker","info","--format","{{.ServerVersion}}|{{.MemTotal}}|{{.NCPU}}"),Duration.ofSeconds(4),s->{},()->false).split("\\|");return Map.of("ready",true,"engine",info[0],"memoryBytes",Long.parseLong(info[1]),"cpuCount",Integer.parseInt(info[2]));}
        catch(Exception e){return Map.of("ready",false,"message","网站运行引擎尚未就绪，请启动 Docker Desktop 后重试");}
    }
    private synchronized void builder(String job,Consumer<String> log,BooleanSupplier cancelled)throws Exception {
        boolean existing=false;try{command(job,List.of("docker","buildx","inspect","sj-hosting-builder"),Duration.ofSeconds(5),s->{},cancelled);existing=true;}catch(Exception ignored){}
        log.accept("正在准备隔离构建环境与依赖下载入口");
        try{command(job,List.of("docker","network","inspect","sj-build-isolated"),Duration.ofSeconds(5),s->{},cancelled);}catch(Exception e){command(job,List.of("docker","network","create","--internal","--label","shijie.owner=hosting","sj-build-isolated"),Duration.ofSeconds(10),log,cancelled);}
        Path work=directory(job).resolve("proxy");Files.createDirectories(work);
        try(var stream=getClass().getResourceAsStream("/hosting/egress-proxy.py")){if(stream==null)throw new IllegalStateException("依赖入口缺失");Files.copy(stream,work.resolve("proxy.py"),StandardCopyOption.REPLACE_EXISTING);}
        try(var stream=getClass().getResourceAsStream("/hosting/ingress-proxy.py")){Files.copy(stream,work.resolve("ingress.py"),StandardCopyOption.REPLACE_EXISTING);}
        Files.writeString(work.resolve("Dockerfile"),"FROM python:3.12-alpine\nCOPY proxy.py /proxy.py\nCOPY ingress.py /ingress.py\nUSER 10001:10001\nCMD [\"python\",\"/proxy.py\"]\n");
        String proxyImage="shijie-build-proxy:v09-"+com.shijie.accessibility.common.Hashes.sha256(Files.readString(work.resolve("proxy.py"))+Files.readString(work.resolve("ingress.py"))).substring(0,12);
        try{inspectImage(job,proxyImage);}catch(Exception absent){command(job,List.of("docker","build","--tag",proxyImage,work.toString()),Duration.ofMinutes(5),log,cancelled);}
        try{String current=command(job,List.of("docker","inspect","--format","{{.Config.Image}}","sj-build-egress"),Duration.ofSeconds(5),line->{},cancelled);if(!proxyImage.equals(current))command(job,List.of("docker","rm","--force","sj-build-egress"),Duration.ofSeconds(10),line->{},cancelled);}catch(Exception absent){}
        String parentProxy=command(job,List.of("docker","info","--format","{{.HTTPProxy}}"),Duration.ofSeconds(5),line->{},cancelled);if(!parentProxy.isBlank()&&!parentProxy.contains("://"))parentProxy="http://"+parentProxy;
        try{command(job,List.of("docker","container","inspect","sj-build-egress"),Duration.ofSeconds(5),s->{},cancelled);command(job,List.of("docker","start","sj-build-egress"),Duration.ofSeconds(10),s->{},cancelled);}
        catch(Exception e){command(job,List.of("docker","run","--detach","--restart","unless-stopped","--name","sj-build-egress","--label","shijie.owner=hosting","--network","sj-build-isolated","--read-only","--cap-drop","ALL","--security-opt","no-new-privileges","--pids-limit","64","--memory","128m","--cpus","0.25","--env","SHIJIE_PARENT_PROXY="+parentProxy,proxyImage),Duration.ofSeconds(30),log,cancelled);}
        try{command(job,List.of("docker","network","connect","bridge","sj-build-egress"),Duration.ofSeconds(10),s->{},cancelled);}catch(Exception ignored){}
        boolean proxyReady=false;for(int i=0;i<20;i++){try{command(job,List.of("docker","exec","sj-build-egress","python","-c","import socket; socket.create_connection(('127.0.0.1',3128),2).close()"),Duration.ofSeconds(4),line->{},cancelled);proxyReady=true;break;}catch(Exception starting){if(cancelled.getAsBoolean())throw starting;Thread.sleep(500);}}if(!proxyReady)throw new IOException("依赖下载入口未能启动，请重试部署");
        if(existing){command(job,List.of("docker","buildx","inspect","sj-hosting-builder","--bootstrap"),Duration.ofMinutes(2),log,cancelled);return;}
        command(job,List.of("docker","buildx","create","--name","sj-hosting-builder","--driver","docker-container","--buildkitd-flags=--debug","--driver-opt","network=sj-build-isolated","--driver-opt","memory=2g","--driver-opt","cpu-quota=200000","--driver-opt","cpu-period=100000","--driver-opt","env.HTTP_PROXY=http://sj-build-egress:3128","--driver-opt","env.HTTPS_PROXY=http://sj-build-egress:3128","--bootstrap"),Duration.ofMinutes(5),log,cancelled);
    }
    public Images build(String job,Path source,RuntimeConfig config,Consumer<String> log,BooleanSupplier cancelled)throws Exception {
        config.validate(source);builder(job,log,cancelled);Path context=directory(job).resolve("build");
        if(Files.exists(context))storage.deleteTree(context);Files.createDirectories(context);storage.copyTree(source,context.resolve("src"));
        String dockerfile;
        if(config.kind().equals("CUSTOM")){
            dockerfile=Files.readString(source.resolve(config.root()).resolve("Dockerfile"));validateCustom(dockerfile);
            // Custom build contexts are limited to the selected immutable source root.
            storage.deleteTree(context.resolve("src"));storage.copyTree(source.resolve(config.root()),context.resolve("src"));
            Files.writeString(context.resolve("src/.dockerignore"),".git\n.env\n.env.*\nnode_modules\n");
            context=context.resolve("src");
        }else{
            boolean lock=Files.exists(source.resolve(config.root()).resolve("package-lock.json"));
            dockerfile=RuntimeTemplates.dockerfile(config,json,lock,proxyAddress(job));String nginx=RuntimeTemplates.nginx("/api/",0);
            if(config.kind().equals("STATIC")){Path webRoot=context.resolve("src").resolve(config.root());if(!Files.exists(webRoot.resolve("index.html"))){try(var paths=Files.walk(webRoot)){var entry=paths.filter(p->Files.isRegularFile(p)&&p.getFileName().toString().matches("(?i).*\\.html?")).sorted().findFirst();if(entry.isPresent())nginx=RuntimeTemplates.staticNginx(webRoot.relativize(entry.get()).toString().replace('\\','/'));}}}
            Files.writeString(context.resolve("nginx.conf"),nginx);
        }
        Files.writeString(context.resolve("Dockerfile"),dockerfile);
        String image="shijie-site:"+job.toLowerCase(Locale.ROOT);buildImage(job,context,"Dockerfile",image,log,cancelled);
        String frontend=null;
        if(config.frontendRoot()!=null&&!config.frontendRoot().isBlank()){
            // Separate static sidecar keeps frontend and backend on one browser origin.
            RuntimeConfig detected=RuntimeConfig.detect(source.resolve(config.frontendRoot()),json);
            var front=new RuntimeConfig("FRONTEND",config.frontendRoot(),8080,detected.buildCommand(),"",detected.outputDirectory(),"/","NONE","","/api/");
            if(config.kind().equals("CUSTOM"))throw new IllegalArgumentException("自定义镜像请自行提供前后端入口");
            Files.writeString(context.resolve("Frontend.Dockerfile"),RuntimeTemplates.dockerfile(front,json,Files.exists(source.resolve(config.frontendRoot()).resolve("package-lock.json")),proxyAddress(job)));
            Files.writeString(context.resolve("nginx.conf"),RuntimeTemplates.nginx(config.apiPrefix(),config.port()));
            frontend="shijie-frontend:"+job.toLowerCase(Locale.ROOT);buildImage(job,context,"Frontend.Dockerfile",frontend,log,cancelled);
        }
        return new Images(inspectImage(job,image),frontend==null?null:inspectImage(job,frontend));
    }
    String proxyAddress(String job)throws Exception{Map<?,?> networks=json.readValue(command(job,List.of("docker","inspect","--format","{{json .NetworkSettings.Networks}}","sj-build-egress"),Duration.ofSeconds(5),line->{},()->false),Map.class);String address=String.valueOf(((Map<?,?>)networks.get("sj-build-isolated")).get("IPAddress"));if(!address.matches("[0-9.]+"))throw new IOException("依赖入口地址无效");return address;}
    void buildImage(String job,Path context,String file,String tag,Consumer<String> log,BooleanSupplier cancelled)throws Exception {
        String proxy="http://"+proxyAddress(job)+":3128";
        var args=List.of("docker","buildx","build","--builder","sj-hosting-builder","--load","--progress","plain","--build-arg","HTTP_PROXY="+proxy,"--build-arg","HTTPS_PROXY="+proxy,"--build-arg","http_proxy="+proxy,"--build-arg","https_proxy="+proxy,"--build-arg","NPM_CONFIG_PROXY="+proxy,"--build-arg","NPM_CONFIG_HTTPS_PROXY="+proxy,"--tag",tag,"--file",context.resolve(file).toString(),context.toString());
        for(int attempt=1;attempt<=3;attempt++){
            try{command(job,args,Duration.ofMinutes(10),log,cancelled);return;}
            catch(IOException failure){
                String message=String.valueOf(failure.getMessage());
                boolean registryFailure=(message.contains("failed to fetch anonymous token")||message.contains("failed to resolve source metadata"))
                        &&(message.contains("timeout")||message.contains("EOF")||message.contains("connection reset"));
                if(!registryFailure||attempt==3||cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw failure;
                log.accept("镜像仓库连接暂时中断，正在重试下载（"+(attempt+1)+"/3）");
                Thread.sleep(1000);
            }
        }
    }
    String inspectImage(String job,String image)throws Exception{return command(job,List.of("docker","image","inspect","--format","{{.Id}}",image),Duration.ofSeconds(5),s->{},()->false);}
    public static void validateCustom(String dockerfile){
        if(dockerfile.length()>50000||dockerfile.matches("(?is).*--(?:network\\s*=\\s*host|security\\s*=\\s*insecure).*"))throw new IllegalArgumentException("自定义构建不能使用宿主网络或特权权限");
        if(java.util.regex.Pattern.compile("(?im)^\\s*ADD\\s+.*https?://").matcher(dockerfile).find())throw new IllegalArgumentException("远程文件请在受控构建步骤中下载");
        if(java.util.regex.Pattern.compile("(?im)^\\s*#\\s*syntax\\s*=").matcher(dockerfile).find())throw new IllegalArgumentException("自定义构建使用平台默认 Dockerfile 解析器");
        Set<String> stages=new HashSet<>();
        for(String line:dockerfile.split("\\R")){
            String[] words=line.strip().split("\\s+");if(words.length>1&&words[0].equalsIgnoreCase("FROM")){
                String image=words[1];if(image.startsWith("--"))throw new IllegalArgumentException("请使用普通 FROM 镜像声明");
                if(!stages.contains(image)&&!image.equals("scratch")&&(!image.matches("[a-zA-Z0-9._/:@-]+")||image.contains("$")||image.matches("^[^/]*[:.][^/]*/.*")&&!image.startsWith("docker.io/")&&!image.startsWith("ghcr.io/")&&!image.startsWith("quay.io/")))throw new IllegalArgumentException("基础镜像只允许公开镜像仓库和已声明构建阶段");
                if(words.length>=4&&words[2].equalsIgnoreCase("AS"))stages.add(words[3]);
            }
        }
    }
    public synchronized String ensureNetwork(String job,String name)throws Exception {
        try{command(job,List.of("docker","network","inspect",name),Duration.ofSeconds(5),s->{},()->false);return name;}catch(Exception absent){}
        if(!networkPrefix.matches("10\\.(?:[0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])"))throw new IOException("APP_HOSTING_NETWORK_PREFIX 必须是 10.x 私有网段前缀");
        for(int attempt=0;attempt<64;attempt++){
            int slot=Math.floorMod(name.hashCode()+attempt,4096);String subnet=networkPrefix+"."+(slot/16)+"."+(slot%16*16)+"/28";
            try{command(job,List.of("docker","network","create","--internal","--subnet",subnet,"--label","shijie.owner=hosting",name),Duration.ofSeconds(10),s->{},()->false);return name;}
            catch(IOException conflict){if(!conflict.getMessage().contains("overlap"))throw conflict;}
        }
        throw new IOException("隔离网段分配失败，请配置未占用的 APP_HOSTING_NETWORK_PREFIX");
    }
    public void database(String job,String engine,String name,String network,String volume,String password,Consumer<String> log,BooleanSupplier cancelled)throws Exception {
        ensureNetwork(job,network);
        try{command(job,List.of("docker","container","inspect",name),Duration.ofSeconds(5),s->{},cancelled);command(job,List.of("docker","start",name),Duration.ofSeconds(10),s->{},cancelled);}
        catch(Exception e){
            String image=engine.equals("MYSQL")?"mysql:8.4":"postgres:16-alpine";
            command(job,List.of("docker","pull",image),Duration.ofMinutes(5),log,cancelled);
            Path env=directory(job).resolve("database.env");
            String contents=engine.equals("MYSQL")?"MYSQL_DATABASE=site\nMYSQL_USER=site\nMYSQL_PASSWORD="+password+"\nMYSQL_ROOT_PASSWORD="+UUID.randomUUID()+UUID.randomUUID()+"\n":"POSTGRES_DB=site\nPOSTGRES_USER=site\nPOSTGRES_PASSWORD="+password+"\n";
            Files.writeString(env,contents);
            try{command(job,List.of("docker","run","--detach","--restart","unless-stopped","--name",name,"--label","shijie.owner=hosting","--network",network,"--network-alias","database","--memory","1g","--cpus","1","--pids-limit","256","--env-file",env.toString(),"--mount","type=volume,src="+volume+",dst="+(engine.equals("MYSQL")?"/var/lib/mysql":"/var/lib/postgresql/data"),image),Duration.ofSeconds(30),log,cancelled);}finally{Files.deleteIfExists(env);}
        }
        for(int i=0;i<60;i++){
            if(cancelled.getAsBoolean())throw new IOException("操作已取消");
            try{List<String> check=engine.equals("MYSQL")?List.of("docker","exec",name,"sh","-c","MYSQL_PWD=\"$MYSQL_PASSWORD\" mysql --user=site --database=site --execute='SELECT 1'"):List.of("docker","exec",name,"pg_isready","--host=127.0.0.1","--username=site","--dbname=site");command(job,check,Duration.ofSeconds(3),s->{},cancelled);return;}catch(Exception ignored){}
            Thread.sleep(1000);
        }throw new IOException("数据库未能就绪，请检查数据服务日志");
    }
    public int start(String job,String site,String environment,String network,String image,String frontend,RuntimeConfig config,Map<String,String> environmentValues,Consumer<String> log,BooleanSupplier cancelled)throws Exception {
        ensureNetwork(job,network);String app="sj-app-"+job.toLowerCase(Locale.ROOT),volume="sj-data-"+(environment.equals("HOST")?site:job).toLowerCase(Locale.ROOT);
        ensureLocalImage(job,"busybox:1.37",log,cancelled);
        command(job,List.of("docker","run","--rm","--network","none","--cap-drop","ALL","--cap-add","CHOWN","--mount","type=volume,src="+volume+",dst=/data","busybox:1.37","chown","10001:10001","/data"),Duration.ofSeconds(15),s->{},cancelled);
        Path env=directory(job).resolve("runtime.env");StringBuilder values=new StringBuilder();
        Map<String,String> all=new LinkedHashMap<>(environmentValues);all.put("PORT",String.valueOf(config.port()));all.put("SERVER_PORT",String.valueOf(config.port()));all.put("HOST","0.0.0.0");all.put("HOME","/tmp");all.put("NPM_CONFIG_CACHE","/tmp/npm-cache");all.put("DATA_DIRECTORY","/data");all.forEach((k,v)->values.append(k).append('=').append(v).append('\n'));Files.writeString(env,values);
        var args=new ArrayList<>(List.of("docker","run","--detach","--restart","unless-stopped","--name",app,"--label","shijie.owner=hosting","--label","shijie.deployment="+job,"--network",network,"--network-alias","backend","--read-only","--cap-drop","ALL","--security-opt","no-new-privileges","--user","10001:10001","--memory","1g","--memory-swap","1g","--cpus","1","--pids-limit","128","--tmpfs","/tmp:rw,noexec,nosuid,size=128m","--mount","type=volume,src="+volume+",dst=/data","--env-file",env.toString()));
        args.add(image);
        try{command(job,args,Duration.ofSeconds(30),log,cancelled);}finally{Files.deleteIfExists(env);}
        String entry=app;int entryPort=config.port();
        if(frontend!=null){entry="sj-web-"+job.toLowerCase(Locale.ROOT);entryPort=8080;command(job,List.of("docker","run","--detach","--restart","unless-stopped","--name",entry,"--label","shijie.owner=hosting","--network",network,"--network-alias","frontend","--read-only","--cap-drop","ALL","--security-opt","no-new-privileges","--user","10001:10001","--memory","128m","--cpus","0.25","--pids-limit","64","--tmpfs","/tmp:rw,noexec,nosuid,size=64m",frontend),Duration.ofSeconds(30),log,cancelled);}
        try{command(job,List.of("docker","network","inspect","sj-ingress"),Duration.ofSeconds(5),line->{},cancelled);}catch(Exception absent){command(job,List.of("docker","network","create","--label","shijie.owner=hosting","sj-ingress"),Duration.ofSeconds(10),log,cancelled);}
        String ingress="sj-entry-"+job.toLowerCase(Locale.ROOT);
        String proxyImage=command(job,List.of("docker","inspect","--format","{{.Config.Image}}","sj-build-egress"),Duration.ofSeconds(5),line->{},cancelled);
        command(job,List.of("docker","run","--detach","--restart","unless-stopped","--name",ingress,"--label","shijie.owner=hosting","--network","sj-ingress","--read-only","--cap-drop","ALL","--security-opt","no-new-privileges","--user","10001:10001","--memory","128m","--memory-swap","128m","--cpus","0.25","--pids-limit","64","--env","UPSTREAM_HOST="+(frontend==null?"backend":"frontend"),"--env","UPSTREAM_PORT="+entryPort,"--publish","127.0.0.1::8080",proxyImage,"python","/ingress.py"),Duration.ofSeconds(30),log,cancelled);
        command(job,List.of("docker","network","connect",network,ingress),Duration.ofSeconds(10),line->{},cancelled);
        String ports=command(job,List.of("docker","port",ingress,"8080/tcp"),Duration.ofSeconds(5),line->{},cancelled);
        return Integer.parseInt(ports.lines().filter(line->line.startsWith("127.0.0.1:")).findFirst().orElseThrow().substring(10));
    }
    void ensureLocalImage(String job,String image,Consumer<String> log,BooleanSupplier cancelled)throws Exception {
        try {
            command(job,List.of("docker","image","inspect","--format","{{.Id}}",image),Duration.ofSeconds(5),s->{},cancelled);
        } catch(IOException missing) {
            command(job,List.of("docker","pull",image),Duration.ofMinutes(3),log,cancelled);
        }
    }
    public String logs(String job){try{return command(job,List.of("docker","logs","--tail","80","sj-app-"+job.toLowerCase(Locale.ROOT)),Duration.ofSeconds(5),s->{},()->false);}catch(Exception e){return "无法读取应用日志";}}
    public void stop(String job){for(String prefix:List.of("sj-app-","sj-web-","sj-entry-"))try{command(job,List.of("docker","rm","--force",prefix+job.toLowerCase(Locale.ROOT)),Duration.ofSeconds(15),s->{},()->false);}catch(Exception ignored){} }

    /** Remove only resource names derived from this website's recorded IDs, never shared images by digest. */
    public void deleteWebsite(String site,List<String> deployments,List<String> databases)throws Exception {
        hostingId(site); deployments.forEach(this::hostingId); databases.forEach(this::hostingId);
        String cleanup="delete-"+site;
        if (!deployments.isEmpty() || !databases.isEmpty()) {
            Set<String> containers=resourceNames(cleanup,"container"), volumes=resourceNames(cleanup,"volume"), networks=resourceNames(cleanup,"network"), images=resourceNames(cleanup,"image");
            for (String id:deployments) for (String prefix:List.of("sj-app-","sj-web-","sj-entry-"))
                removeResource(cleanup,"container",prefix+id.toLowerCase(Locale.ROOT),containers);
            for (String id:databases) removeResource(cleanup,"container","sj-db-"+id.toLowerCase(Locale.ROOT),containers);
            for (String id:deployments) {
                String key=id.toLowerCase(Locale.ROOT);
                removeResource(cleanup,"volume","sj-data-"+key,volumes);
                removeResource(cleanup,"network","sj-net-"+key,networks);
                removeResource(cleanup,"image","shijie-site:"+key,images);
                removeResource(cleanup,"image","shijie-frontend:"+key,images);
            }
            for (String id:databases) {
                String key=id.toLowerCase(Locale.ROOT);
                removeResource(cleanup,"volume","sj-dbdata-"+key,volumes);
                removeResource(cleanup,"network","sj-net-db-"+key,networks);
            }
            removeResource(cleanup,"volume","sj-data-"+site.toLowerCase(Locale.ROOT),volumes);
        }
        for (String id:deployments) storage.deleteTreeStrict(directory(id));
        storage.deleteTreeStrict(directory(cleanup));
    }

    private void hostingId(String id) { if (id==null || !id.matches("[a-zA-Z0-9]{26}")) throw new IllegalArgumentException("无效网站资源编号"); }
    private Set<String> resourceNames(String job,String type)throws Exception {
        String format=type.equals("image")?"{{.Repository}}:{{.Tag}}":"{{.Name}}";
        if (type.equals("container")) format="{{.Names}}";
        var args=new ArrayList<>(List.of("docker",type,"ls"));
        if (type.equals("container")) args.add("--all");
        args.addAll(List.of("--format",format));
        String output=command(job,args,Duration.ofSeconds(15),line->{},()->false);
        return new HashSet<>(output.lines().filter(s->!s.isBlank()).toList());
    }
    private void removeResource(String job,String type,String name,Set<String> existing)throws Exception {
        if (!existing.contains(name)) return;
        var args=new ArrayList<>(List.of("docker",type,"rm"));
        if (type.equals("container")) args.add("--force");
        args.add(name);
        command(job,args,Duration.ofSeconds(30),line->{},()->false);
        existing.remove(name);
    }
    public int accessiblePort(String job)throws Exception{String ports=command("monitor-"+job,List.of("docker","port","sj-entry-"+job.toLowerCase(Locale.ROOT),"8080/tcp"),Duration.ofSeconds(5),line->{},()->false);return Integer.parseInt(ports.lines().filter(line->line.startsWith("127.0.0.1:")).findFirst().orElseThrow().substring(10));}
    public boolean alive(String job){try{for(String prefix:List.of("sj-app-","sj-entry-"))if(!"true".equals(command(job,List.of("docker","inspect","--format","{{.State.Running}}",prefix+job.toLowerCase(Locale.ROOT)),Duration.ofSeconds(4),line->{},()->false)))return false;return true;}catch(Exception e){return false;}}
    public String schemaHash(String job,String database,String engine)throws Exception {
        String sql=engine.equals("MYSQL")?"SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COALESCE(COLUMN_DEFAULT,''),COLUMN_KEY,EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='site' ORDER BY TABLE_NAME,ORDINAL_POSITION; SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,COLUMN_NAME,SEQ_IN_INDEX,INDEX_TYPE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='site' ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX; SELECT TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA='site' ORDER BY TABLE_NAME,CONSTRAINT_NAME;":"SELECT table_name,column_name,data_type,is_nullable,COALESCE(column_default,'') FROM information_schema.columns WHERE table_schema='public' ORDER BY table_name,ordinal_position; SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY tablename,indexname; SELECT c.relname,p.conname,pg_get_constraintdef(p.oid) FROM pg_constraint p JOIN pg_class c ON c.oid=p.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' ORDER BY c.relname,p.conname;";
        Path query=directory(job).resolve("schema-query.sql"),output=directory(job).resolve("schema-result.txt");Files.createDirectories(query.getParent());Files.writeString(query,sql);
        List<String> args=engine.equals("MYSQL")?List.of("docker","exec","-i",database,"sh","-c","MYSQL_PWD=$MYSQL_PASSWORD exec mysql --user=site --batch --skip-column-names site"):List.of("docker","exec","-i",database,"psql","-U","site","-d","site","-At","--set","ON_ERROR_STOP=1");
        try{Process process=new ProcessBuilder(args).redirectInput(query.toFile()).redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();if(!process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("数据库结构检查超时");}if(process.exitValue()!=0||Files.size(output)>5L*1024*1024)throw new IOException("无法完整读取数据库结构，本版本不能自动发布");return com.shijie.accessibility.common.Hashes.sha256(output);}finally{Files.deleteIfExists(query);Files.deleteIfExists(output);}
    }
    public void stopDatabase(String job,String name)throws Exception{command(job,List.of("docker","stop","--time","2",name),Duration.ofSeconds(10),line->{},()->false);}
    public synchronized void backup(String job,String database,String engine,Path destination)throws Exception {
        var args=engine.equals("MYSQL")?List.of("docker","exec",database,"sh","-c","MYSQL_PWD=\"$MYSQL_PASSWORD\" mysqldump --user=site --single-transaction --no-tablespaces site"):List.of("docker","exec",database,"pg_dump","-U","site","-d","site","--no-owner","--no-acl");
        boolean running="true".equals(command(job,List.of("docker","inspect","--format","{{.State.Running}}",database),Duration.ofSeconds(5),line->{},()->false));
        if(!running)command(job,List.of("docker","start",database),Duration.ofSeconds(15),line->{},()->false);
        try{
        if(!running)Thread.sleep(5000);
        Process process=new ProcessBuilder(args).redirectOutput(destination.toFile()).redirectError(ProcessBuilder.Redirect.PIPE).start();
        if(!process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("备份超时");}if(process.exitValue()!=0){Files.deleteIfExists(destination);throw new IOException("数据备份失败");}
        }finally{if(!running)stopDatabase(job,database);}
    }
    public void restore(String job,String database,String engine,Path backup)throws Exception{
        var args=engine.equals("MYSQL")?List.of("docker","exec","-i",database,"sh","-c","MYSQL_PWD=\"$MYSQL_PASSWORD\" mysql --user=site site"):List.of("docker","exec","-i",database,"psql","-U","site","-d","site","--set","ON_ERROR_STOP=1");
        Process process=new ProcessBuilder(args).redirectInput(backup.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("初始化测试数据超时");}if(process.exitValue()!=0)throw new IOException("测试数据库复制失败");
    }
    public void backupFiles(String job,String site,String environment,Path destination)throws Exception {
        String volume="sj-data-"+(environment.equals("HOST")?site:job).toLowerCase(Locale.ROOT);
        command("backup-"+job,List.of("docker","volume","inspect",volume),Duration.ofSeconds(5),line->{},()->false);
        String helper="sj-backup-"+UUID.randomUUID();
        var args=List.of("docker","run","--rm","--name",helper,"--network","none","--read-only","--cap-drop","ALL","--security-opt","no-new-privileges","--user","10001:10001","--memory","64m","--cpus","0.25","--pids-limit","16","--mount","type=volume,src="+volume+",dst=/data,readonly","busybox:1.37","tar","-C","/data","-czf","-",".");
        Process process=new ProcessBuilder(args).redirectOutput(destination.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        try{while(!process.waitFor(200,java.util.concurrent.TimeUnit.MILLISECONDS)){if(System.nanoTime()>deadline||Files.size(destination)>100L*1024*1024)throw new IOException("网站文件备份超过 100 MB 或 60 秒限制，请缩小数据范围后重试");}if(Files.size(destination)>100L*1024*1024)throw com.shijie.accessibility.common.ApiException.badRequest("BACKUP_SIZE_LIMIT","网站文件备份超过 100 MB，请缩小数据范围后重试");if(process.exitValue()!=0)throw new IOException("网站文件备份未完成，请检查数据卷是否可读");}
        finally{if(process.isAlive())process.destroyForcibly();try{command("backup-"+job,List.of("docker","rm","--force",helper),Duration.ofSeconds(10),line->{},()->false);}catch(Exception ignored){}}
    }
    public record Images(String backend,String frontend){}
}
