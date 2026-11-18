package com.shijie.accessibility.hosting;

import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

public final class RuntimeTemplates {
    private RuntimeTemplates(){}
    static String rooted(RuntimeConfig c){return c.root()==null||c.root().isBlank()?"src":"src/"+c.root();}
    static String command(String value,String fallback){return value==null||value.isBlank()?fallback:value;}
    public static String dockerfile(RuntimeConfig c,ObjectMapper json,boolean hasLock){
        return dockerfile(c,json,hasLock,"sj-build-egress");
    }
    public static String dockerfile(RuntimeConfig c,ObjectMapper json,boolean hasLock,String proxyHost){
        String root=rooted(c),install=hasLock?"npm ci":"npm install";
        String build=c.buildCommand()==null||c.buildCommand().isBlank()?"":"RUN "+c.buildCommand()+"\n";
        return switch(c.kind()){
            case "STATIC" -> "FROM nginx:1.28-alpine\nCOPY --chown=101:101 "+json.writeValueAsString(root+"/")+" /site/\nCOPY nginx.conf /etc/nginx/nginx.conf\nUSER 101:101\nEXPOSE 8080\n";
            case "FRONTEND" -> "FROM node:22-alpine AS build\nARG NPM_CONFIG_PROXY\nARG NPM_CONFIG_HTTPS_PROXY\nWORKDIR /build\nCOPY "+json.writeValueAsString(root+"/")+" ./\nRUN "+install+"\n"+build+"FROM nginx:1.28-alpine\nCOPY --from=build --chown=101:101 /build/"+command(c.outputDirectory(),"dist")+"/ /site/\nCOPY nginx.conf /etc/nginx/nginx.conf\nUSER 101:101\nEXPOSE 8080\n";
            case "NODE" -> "FROM node:22-alpine\nARG NPM_CONFIG_PROXY\nARG NPM_CONFIG_HTTPS_PROXY\nWORKDIR /app\nCOPY --chown=node:node "+json.writeValueAsString(root+"/")+" ./\nRUN "+install+"\n"+build+"USER node\nENV NODE_ENV=production\nCMD "+json.writeValueAsString(new String[]{"sh","-lc",command(c.startCommand(),"npm start")})+"\n";
            case "JAVA" -> "FROM maven:3.9-eclipse-temurin-21 AS build\nWORKDIR /build\nCOPY "+json.writeValueAsString(root+"/")+" ./\n"+"RUN mkdir -p /root/.m2 && echo '<settings><proxies><proxy><id>hosting-http</id><active>true</active><protocol>http</protocol><host>"+proxyHost+"</host><port>3128</port></proxy><proxy><id>hosting-https</id><active>true</active><protocol>https</protocol><host>"+proxyHost+"</host><port>3128</port></proxy></proxies></settings>' > /root/.m2/settings.xml\nRUN "+command(c.buildCommand(),"mvn -B -DskipTests package")+"\nRUN find target -maxdepth 1 -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' -exec cp {} /app.jar \\;\nFROM eclipse-temurin:21-jre\nWORKDIR /app\nCOPY --from=build /app.jar /app/app.jar\nUSER 10001:10001\nCMD "+json.writeValueAsString(new String[]{"sh","-lc",command(c.startCommand(),"java -jar /app/app.jar")})+"\n";
            case "PYTHON" -> "FROM python:3.12-slim\nWORKDIR /app\nCOPY "+json.writeValueAsString(root+"/")+" ./\nRUN if [ -f requirements.txt ]; then pip install --no-cache-dir -r requirements.txt; elif [ -f pyproject.toml ]; then pip install --no-cache-dir .; fi\n"+build+"USER 10001:10001\nENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1\nCMD "+json.writeValueAsString(new String[]{"sh","-lc",command(c.startCommand(),"python app.py")})+"\n";
            default -> throw new IllegalArgumentException("自定义项目应使用自己的 Dockerfile");
        };
    }
    public static String nginx(String apiPrefix,int backendPort){
        return "pid /tmp/nginx.pid;\nerror_log /dev/stderr;\nevents { worker_connections 256; }\nhttp { include /etc/nginx/mime.types; access_log off; client_body_temp_path /tmp/client; proxy_temp_path /tmp/proxy; fastcgi_temp_path /tmp/fastcgi; uwsgi_temp_path /tmp/uwsgi; scgi_temp_path /tmp/scgi; server { listen 8080; absolute_redirect off; root /site; index index.html;"+(backendPort==0?"":" location "+apiPrefix+" { proxy_pass http://backend:"+backendPort+"; proxy_set_header Host $http_host; proxy_set_header X-Forwarded-Host $http_host; proxy_set_header X-Forwarded-Proto http; proxy_http_version 1.1; proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection upgrade; }")+" location / { try_files $uri $uri/ /index.html; } } }\n";
    }
    public static String staticNginx(String entry){
        RuntimeConfig.safePath(entry);
        String config=nginx("/api/",0);
        if(entry.equals("index.html"))return config;
        String url=Arrays.stream(entry.split("/")).map(part->java.net.URLEncoder.encode(part,StandardCharsets.UTF_8).replace("+","%20")).collect(java.util.stream.Collectors.joining("/"));
        config=config.replace("try_files $uri $uri/ /index.html;","try_files $uri $uri/ \"/"+entry+"\";");
        return config.replace("location / {","location = / { return 302 /"+url+"; } location / {");
    }
}
