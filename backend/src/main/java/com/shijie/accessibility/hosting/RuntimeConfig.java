package com.shijie.accessibility.hosting;

import com.shijie.accessibility.common.ApiException;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

public record RuntimeConfig(String kind,String root,int port,String buildCommand,String startCommand,
                            String outputDirectory,String healthPath,String database,String frontendRoot,String apiPrefix) {
    public static RuntimeConfig detect(Path source,ObjectMapper json) {
        Path root=source;
        try(var children=Files.list(source)){
            var all=children.toList();if(all.size()==1&&Files.isDirectory(all.getFirst()))root=all.getFirst();
        } catch(Exception ignored){}
        String relative=source.relativize(root).toString().replace('\\','/');
        String kind="STATIC",build="",start="",output="",frontend="";int port=8080;
        try {
            if(Files.isRegularFile(root.resolve("shijie.json"))) {
                var config=json.readValue(Files.readString(root.resolve("shijie.json")),RuntimeConfig.class);config.validate(source);return config;
            }
            if(Files.exists(root.resolve("Dockerfile")))kind="CUSTOM";
            else if(Files.exists(root.resolve("pom.xml"))){kind="JAVA";build="mvn -B -DskipTests package";start="java -jar /app/app.jar";}
            else if(Files.exists(root.resolve("package.json"))){
                Map<?,?> pkg=json.readValue(Files.readString(root.resolve("package.json")),Map.class);
                var scripts=pkg.get("scripts") instanceof Map<?,?> m?m:Map.of();
                String text=Files.readString(root.resolve("package.json"));
                kind=(text.contains("\"vite\"")||text.contains("react-scripts"))&&!text.contains("\"next\"")?"FRONTEND":"NODE";
                build=scripts.containsKey("build")?"npm run build":"";start=scripts.containsKey("start")?"npm start":"node server.js";
                if(kind.equals("FRONTEND"))output=text.contains("react-scripts")?"build":"dist";
                port=kind.equals("NODE")?3000:8080;
            }else if(Files.exists(root.resolve("requirements.txt"))||Files.exists(root.resolve("pyproject.toml"))||Files.exists(root.resolve("app.py"))){kind="PYTHON";port=8000;start=Files.exists(root.resolve("manage.py"))?"python manage.py runserver 0.0.0.0:8000":"python app.py";}
            else if(Files.exists(root.resolve("backend/pom.xml"))||Files.exists(root.resolve("backend/package.json"))||Files.exists(root.resolve("backend/requirements.txt"))){var child=detect(root.resolve("backend"),json);relative=relative.isBlank()?"backend":relative+"/backend";kind=child.kind();build=child.buildCommand();start=child.startCommand();port=child.port();if(Files.exists(root.resolve("frontend/package.json")))frontend=source.relativize(root.resolve("frontend")).toString().replace('\\','/');}
            else {
                var modules=detectModules(source,root,json);
                if(modules!=null)return modules;
            }
        }catch(ApiException e){throw e;}catch(Exception e){throw ApiException.badRequest("RUNTIME_DETECTION_FAILED","项目配置无法识别，请检查 shijie.json 或 package.json");}
        var config=new RuntimeConfig(kind,relative,port,build,start,output,"/","NONE",frontend,"/api/");config.validate(source);return config;
    }
    private static RuntimeConfig detectModules(Path source,Path root,ObjectMapper json)throws Exception {
        List<Path> javaModules=new ArrayList<>(),frontends=new ArrayList<>();
        try(var children=Files.list(root)){
            for(Path child:children.filter(Files::isDirectory).sorted().toList()){
                if(Files.isRegularFile(child.resolve("pom.xml")))javaModules.add(child);
                else if(Files.isRegularFile(child.resolve("package.json"))&&detect(child,json).kind().equals("FRONTEND"))frontends.add(child);
            }
        }
        if(javaModules.size()>1||frontends.size()>1)throw ApiException.badRequest("AMBIGUOUS_RUNTIME","项目包含多个运行模块，请用 shijie.json 指定网站和前后端目录");
        if(javaModules.size()==1){
            String backend=source.relativize(javaModules.getFirst()).toString().replace('\\','/');
            String frontend=frontends.isEmpty()?"":source.relativize(frontends.getFirst()).toString().replace('\\','/');
            var config=new RuntimeConfig("JAVA",backend,8080,"mvn -B -DskipTests package","java -jar /app/app.jar","","/","NONE",frontend,"/api/");
            config.validate(source);return config;
        }
        if(frontends.size()==1){
            var child=detect(frontends.getFirst(),json);
            var config=new RuntimeConfig(child.kind(),source.relativize(frontends.getFirst()).toString().replace('\\','/'),child.port(),child.buildCommand(),child.startCommand(),child.outputDirectory(),child.healthPath(),child.database(),"",child.apiPrefix());
            config.validate(source);return config;
        }
        return null;
    }
    public void validate(Path source) {
        if(!Set.of("STATIC","FRONTEND","NODE","JAVA","PYTHON","CUSTOM").contains(kind)||port<1024||port>65535||!Set.of("NONE","MYSQL","POSTGRESQL").contains(database))throw ApiException.badRequest("INVALID_RUNTIME","运行模板、端口或数据库类型无效");
        safePath(root);safePath(outputDirectory);safePath(frontendRoot);
        if(Set.of("STATIC","FRONTEND").contains(kind)&&port!=8080)throw ApiException.badRequest("STATIC_PORT_FIXED","前端网站使用平台分配的 8080 端口");
        if(!Files.isDirectory(source.resolve(root==null?"":root)))throw ApiException.badRequest("RUNTIME_ROOT_MISSING","运行目录不存在");
        if(frontendRoot!=null&&!frontendRoot.isBlank()&&!Files.isRegularFile(source.resolve(frontendRoot).resolve("package.json")))throw ApiException.badRequest("FRONTEND_ROOT_MISSING","前端目录应包含 package.json");
        for(String command:List.of(buildCommand==null?"":buildCommand,startCommand==null?"":startCommand))if(command.length()>2000||command.contains("\n")||command.contains("\r"))throw ApiException.badRequest("INVALID_COMMAND","运行命令必须为单行且不超过 2000 字");
        for(String path:List.of(healthPath==null?"":healthPath,apiPrefix==null?"/api/":apiPrefix))if(!path.startsWith("/")||path.contains("\n")||path.contains(";")||path.contains("{")||path.contains("}")||path.contains(" ")||path.contains(".."))throw ApiException.badRequest("INVALID_RUNTIME_PATH","健康检查和接口前缀必须是站内路径");
        if(kind.equals("CUSTOM")&&!Files.isRegularFile(source.resolve(root==null?"":root).resolve("Dockerfile")))throw ApiException.badRequest("DOCKERFILE_MISSING","自定义运行需要 Dockerfile");
    }
    public static void safePath(String path) {
        if(path==null||path.isBlank())return;
        if(!path.matches("[\\p{L}\\p{N}_./ -]{1,200}")||path.startsWith("/")||Arrays.asList(path.replace('\\','/').split("/")).contains(".."))throw ApiException.badRequest("UNSAFE_SOURCE_PATH","源码目录必须是项目内的相对目录");
    }
}
