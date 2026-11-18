package com.shijie.accessibility.scanner;

import com.shijie.accessibility.config.AppProperties;
import com.shijie.accessibility.model.ProjectVersionEntity;
import com.shijie.accessibility.service.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named="playwright.tests",matches="true")
class DemoPerformanceTest {
    @TempDir Path temp;
    @Test void tenStaticPagesCompleteWithinOneMinuteWithTraces() throws Exception {
        var properties=new AppProperties(temp,new AppProperties.Scanner(10,10_000,60_000),AppProperties.Ai.defaults(false));
        var storage=new StorageService(properties);
        var version=new ProjectVersionEntity();version.id="0000000000000000000000000B";version.projectId="0000000000000000000000000A";version.entryPage="page0.html";
        Path source=storage.versionSource(version.projectId,version.id);Files.createDirectories(source);
        List<String> pages=new ArrayList<>();
        for(int i=0;i<10;i++) {
            String name="page"+i+".html";pages.add(name);
            Files.writeString(source.resolve(name),"""
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>校园服务</title>
                <style>body{color:#172033;background:white}button:focus{outline:3px solid blue}.steps{display:flex}.steps p:first-child{order:4}</style></head>
                <body><h1>校园服务</h1><label for="q">检索</label><input id="q"><button>搜索</button>
                <a href="#main">跳至服务</a><main id="main"><h2>办理顺序</h2><div class="steps"><p>确认课程</p><p>填写资料</p><p>核对信息</p><p>提交报名</p></div></main></body></html>
                """);
        }
        long started=System.nanoTime();
        var output=new BrowserScanner(storage,properties).scan(version,pages,"0000000000000000000000000C",()->false);
        double seconds=(System.nanoTime()-started)/1e9;
        assertThat(output.pageErrors()).isEmpty();assertThat(output.pages()).hasSize(10);
        assertThat(output.pages()).allSatisfy(p->{assertThat(p.focusTrace()).containsKey("forwardSteps");assertThat(p.readingTrace()).containsKey("status");assertThat(p.screenshot()).exists();});
        assertThat(seconds).isLessThan(60);
        String record=System.getProperty("performance.record.path");
        if(record!=null){Path report=Path.of(record);Files.createDirectories(report.getParent());
            Files.writeString(report,"{\"pages\":10,\"seconds\":"+seconds+",\"aiEnabled\":false,\"viewport\":\"1440x1000\",\"singleConcurrency\":true,\"java\":\""+System.getProperty("java.version")+"\",\"os\":\""+System.getProperty("os.name")+"\"}");}
    }
}
