package com.shijie.accessibility.service;

import com.shijie.accessibility.model.ProjectEntity;
import com.shijie.accessibility.model.ProjectVersionEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ExampleService {
    private final ProjectService projects;

    public ExampleService(ProjectService projects) { this.projects = projects; }

    public List<Map<String, String>> list() {
        return List.of(
                Map.of("slug", "library", "name", "校园图书馆服务", "description", "包含自然阅读顺序、图片、搜索表单、低对比度与标题问题"),
                Map.of("slug", "course", "name", "课程报名中心", "description", "包含可控 CSS 重排、表单标签、键盘焦点与 ARIA 引用问题"));
    }

    public InstalledExample install(String slug) {
        ExampleDefinition definition = switch (slug) {
            case "library" -> library();
            case "course" -> course();
            default -> throw com.shijie.accessibility.common.ApiException.notFound("示例项目不存在");
        };
        ProjectEntity project = projects.create(definition.name, definition.description);
        ProjectVersionEntity version = projects.importProject(project.id,
                new MemoryMultipartFile(definition.name + ".zip", zip(definition.files)), "index.html");
        return new InstalledExample(project, version);
    }

    private byte[] zip(Map<String, String> files) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                for (var file : files.entrySet()) {
                    zip.putNextEntry(new ZipEntry(file.getKey()));
                    zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private ExampleDefinition library() {
        String html = """
                <!doctype html><html><head><meta charset="utf-8"><title>校园图书馆</title><link rel="stylesheet" href="style.css"></head>
                <body><header><h1>校园图书馆</h1><nav><a href="#services">馆藏服务</a><a href="#hours"></a></nav></header>
                <main><section class="hero"><img src="library.svg"><div><h3>让知识触手可及</h3><p class="muted">借阅、研修空间与学术资源一站办理</p></div></section>
                <section id="services"><h2>馆藏检索</h2><label>请输入书名</label><input id="query"><button>开始检索</button></section>
                <section id="hours"><h4>开放时间</h4><p>周一至周日 08:00—22:00</p><div onclick="alert('预约')" class="fake-button">预约研修室</div></section>
                </main><footer>视界大学图书馆</footer></body></html>
                """;
        String css = """
                *{box-sizing:border-box}body{margin:0;font-family:Arial,"Microsoft YaHei",sans-serif;color:#183153;background:#f7fafc}header{padding:22px 8%;background:#163d70;color:white;display:flex;justify-content:space-between;align-items:center}nav a{color:white;margin-left:22px}.hero{display:grid;grid-template-columns:1.2fr 1fr;gap:36px;align-items:center;padding:64px 8%;background:white}.hero img{width:100%;height:280px}.muted{color:#b5bdc8}section:not(.hero){margin:34px 8%;padding:26px;background:white;border-radius:14px}input{padding:12px;width:60%;margin:8px}button,.fake-button{padding:12px 18px;background:#176b87;color:white;border:0;border-radius:6px;display:inline-block}a:focus,button:focus,input:focus{outline:none}footer{padding:30px 8%;color:#68758a}
                """;
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 400"><rect width="800" height="400" fill="#dcecf4"/><rect x="130" y="110" width="540" height="230" rx="8" fill="#fff"/><path d="M100 120L400 35l300 85" fill="#176b87"/><g fill="#163d70"><rect x="180" y="160" width="60" height="180"/><rect x="300" y="160" width="60" height="180"/><rect x="440" y="160" width="60" height="180"/><rect x="560" y="160" width="60" height="180"/></g></svg>
                """;
        return new ExampleDefinition("校园图书馆服务", "用于演示静态规则检测、模板修复和复测。",
                Map.of("index.html", html, "style.css", css, "library.svg", svg));
    }

    private ExampleDefinition course() {
        String html = """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>课程报名中心</title><link rel="stylesheet" href="style.css"></head>
                <body><header><span class="brand">视界大学</span><h1>课程报名中心</h1></header><main>
                <section><h2>选择课程</h2><div class="course"><h3>数字媒体基础</h3><p>周三 14:00</p><button aria-describedby="missing-tip">立即报名</button></div>
                <div class="course"><h3>网页设计实践</h3><p>周五 09:00</p><button><img src="arrow.svg" alt=""></button></div></section>
                <section><h2>报名信息</h2><form><label>姓名</label><input id="student-name"><label for="email">邮箱</label><input id="email" type="email"><p id="help">我们只发送课程通知</p><select aria-describedby="unknown-help"><option>本科生</option><option>研究生</option></select><button type="submit">提交报名</button></form></section>
                <section class="process"><h2>报名流程演示</h2><ol class="steps"><li>确认课程</li><li>填写资料</li><li>核对信息</li><li>提交报名</li></ol></section>
                </main></body></html>
                """;
        String css = """
                body{margin:0;font-family:"Microsoft YaHei",sans-serif;background:#eef4f7;color:#172033}header{background:#14566e;color:white;padding:28px 9%}.brand{opacity:.8}main{display:grid;grid-template-columns:1fr 1fr;gap:24px;padding:36px 9%}section{background:white;border-radius:16px;padding:26px}.course{border-bottom:1px solid #dce5ea;padding:14px 0}.process{grid-column:1/-1}.steps{display:flex;gap:12px;list-style-position:inside;padding:0}.steps li{flex:1;padding:14px;background:#e4f0f4;border-radius:8px}.steps li:nth-child(1){order:4}.steps li:nth-child(2){order:3}.steps li:nth-child(3){order:2}.steps li:nth-child(4){order:1}label,input,select{display:block;width:100%;margin-top:10px}input,select{padding:11px;border:1px solid #9aabb5;border-radius:6px}button{margin-top:14px;padding:11px 18px;background:#14566e;color:white;border:0;border-radius:6px}button img{width:16px}button:focus,input:focus,select:focus{outline:0;box-shadow:none}
                """;
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><path fill="white" d="M3 9h10l-4-4 1.4-1.4L17 10l-6.6 6.4L9 15l4-4H3z"/></svg>
                """;
        return new ExampleDefinition("课程报名中心", "用于演示 CSS 阅读顺序重排、表单、ARIA 和键盘焦点检测。",
                Map.of("index.html", html, "style.css", css, "arrow.svg", svg));
    }

    private record ExampleDefinition(String name, String description, Map<String, String> files) {}
    public record InstalledExample(ProjectEntity project, ProjectVersionEntity version) {}

    private static class MemoryMultipartFile implements MultipartFile {
        private final String name; private final byte[] bytes;
        MemoryMultipartFile(String name, byte[] bytes) { this.name=name; this.bytes=bytes; }
        public String getName(){return "file";} public String getOriginalFilename(){return name;}
        public String getContentType(){return "application/zip";} public boolean isEmpty(){return bytes.length==0;}
        public long getSize(){return bytes.length;} public byte[] getBytes(){return bytes.clone();}
        public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
        public void transferTo(File dest) throws IOException { try(var out=new FileOutputStream(dest)){out.write(bytes);} }
    }
}
