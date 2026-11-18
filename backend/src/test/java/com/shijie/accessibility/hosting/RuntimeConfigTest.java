package com.shijie.accessibility.hosting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RuntimeConfigTest {
    @TempDir Path root;final ObjectMapper json=new ObjectMapper();
    @Test void identifiesStaticAndWrappedReact()throws Exception{Files.writeString(root.resolve("index.html"),"<html></html>");assertThat(RuntimeConfig.detect(root,json).kind()).isEqualTo("STATIC");Files.delete(root.resolve("index.html"));Files.createDirectory(root.resolve("website"));Files.writeString(root.resolve("website/package.json"),"{\"devDependencies\":{\"vite\":\"6\"},\"scripts\":{\"build\":\"vite build\"}}");var c=RuntimeConfig.detect(root,json);assertThat(c.kind()).isEqualTo("FRONTEND");assertThat(c.root()).isEqualTo("website");assertThat(c.outputDirectory()).isEqualTo("dist");}
    @Test void detectsNodeJavaPythonAndCustom()throws Exception{for(var entry:Map.of("package.json","{\"scripts\":{\"start\":\"node app.js\"}}","pom.xml","<project/>","requirements.txt","flask","Dockerfile","FROM scratch").entrySet()){Files.writeString(root.resolve(entry.getKey()),entry.getValue());assertThat(RuntimeConfig.detect(root,json).kind()).isEqualTo(switch(entry.getKey()){case"package.json"->"NODE";case"pom.xml"->"JAVA";case"requirements.txt"->"PYTHON";default->"CUSTOM";});Files.delete(root.resolve(entry.getKey()));}}
    @Test void detectsSplitSpringAndFrontend()throws Exception{Files.createDirectories(root.resolve("backend"));Files.createDirectories(root.resolve("frontend"));Files.writeString(root.resolve("backend/pom.xml"),"<project/>");Files.writeString(root.resolve("frontend/package.json"),"{}");var c=RuntimeConfig.detect(root,json);assertThat(c.root()).isEqualTo("backend");assertThat(c.frontendRoot()).isEqualTo("frontend");}
    @Test void detectsNamedJavaAndVueModulesInsteadOfServingSourceHtml()throws Exception{
        Files.createDirectories(root.resolve("ai-match-server"));Files.createDirectories(root.resolve("ai-match-frontend"));
        Files.writeString(root.resolve("README.md"),"project documentation");
        Files.writeString(root.resolve("ai-match-server/pom.xml"),"<project/>");
        Files.writeString(root.resolve("ai-match-server/Dockerfile"),"FROM eclipse-temurin:17-jre\nCOPY target/app.jar /app.jar");
        Files.writeString(root.resolve("ai-match-frontend/package.json"),"{\"devDependencies\":{\"vite\":\"5\"},\"scripts\":{\"build\":\"vite build\"}}");
        Files.writeString(root.resolve("ai-match-frontend/index.html"),"<script type=\"module\" src=\"/src/main.js\"></script>");
        var c=RuntimeConfig.detect(root,json);
        assertThat(c.kind()).isEqualTo("JAVA");assertThat(c.root()).isEqualTo("ai-match-server");
        assertThat(c.frontendRoot()).isEqualTo("ai-match-frontend");
        assertThat(c.buildCommand()).isEqualTo("mvn -B -DskipTests package");
    }
    @Test void nestedStaticEntryRedirectKeepsThePreviewOriginAndDoesNotCorruptThePath(){
        assertThat(RuntimeTemplates.staticNginx("ai-match-frontend/index.html"))
                .contains("absolute_redirect off;","return 302 /ai-match-frontend/index.html;","try_files $uri $uri/ \"/ai-match-frontend/index.html\";")
                .doesNotContain("ai-match-frontend\"/",":8080/");
        assertThat(RuntimeTemplates.staticNginx("网页/报名 页面.html")).contains("%20", "try_files $uri $uri/ \"/网页/报名 页面.html\";");
    }
    @Test void refusesTraversalAndDockerInstructionInjection(){assertThatThrownBy(()->RuntimeConfig.safePath("../other")).isInstanceOf(RuntimeException.class);var c=new RuntimeConfig("NODE","",3000,"echo ok\nFROM malicious","npm start","","/","NONE","","/api/");assertThatThrownBy(()->c.validate(root)).isInstanceOf(RuntimeException.class);}
    @Test void isolatesBuildAndRuntimeConfiguration(){var c=new RuntimeConfig("NODE","",3000,"npm run build","npm start","","/","MYSQL","","/api/");String docker=RuntimeTemplates.dockerfile(c,json,true);assertThat(docker).contains("npm ci","USER node").doesNotContain("DB_PASSWORD",".env","--network=host");assertThat(RuntimeTemplates.nginx("/api/",3000)).contains("proxy_pass http://backend:3000","try_files");}
    @Test void rejectsPrivilegedBuildsAndPrivateBaseImages(){for(String source:List.of("FROM alpine\nRUN --network=host echo bad","FROM alpine\nRUN --security=insecure true","FROM 127.0.0.1/private:latest","FROM ${SECRET_IMAGE}"))assertThatThrownBy(()->DockerHostingRuntime.validateCustom(source)).isInstanceOf(IllegalArgumentException.class);assertThatCode(()->DockerHostingRuntime.validateCustom("FROM node:22-alpine AS build\nRUN npm ci\nFROM build")).doesNotThrowAnyException();}
    @Test void rejectsRepositoryCredentialsAndUnsafeBranches(){for(String url:List.of("http://github.com/team/repo","https://token@github.com/team/repo","https://127.0.0.1/repo","https://github.com/team/repo?token=x"))assertThatThrownBy(()->HostingService.validateRepository(url,"main")).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->HostingService.validateRepository("https://github.com/team/repo","--upload-pack=evil")).isInstanceOf(RuntimeException.class);assertThatCode(()->HostingService.validateRepository("https://gitlab.com/team/site.git","release/v09")).doesNotThrowAnyException();}
}
