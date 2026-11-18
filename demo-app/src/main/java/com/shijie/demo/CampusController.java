package com.shijie.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
public class CampusController {
    private final JdbcTemplate jdbc;private final String mode;private final Path uploads;
    private final AtomicInteger insideRedirectProbe=new AtomicInteger();
    private final AtomicInteger outsideRedirectProbe=new AtomicInteger();
    public CampusController(JdbcTemplate jdbc,@Value("${demo.mode:defective}")String mode,@Value("${demo.upload-root:./data/uploads}")Path uploads)throws Exception{this.jdbc=jdbc;this.mode=mode.toLowerCase(Locale.ROOT);this.uploads=uploads.toAbsolutePath().normalize();Files.createDirectories(this.uploads);}

    @GetMapping("/") ResponseEntity<String>home(){return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body("""
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>校园报名测试站</title></head><body>
        <h1>校园报名测试站</h1><p>当前运行模式：%s</p><nav><a href="/api/applications/search?keyword=张">报名查询</a><a href="/api/slow">慢接口</a></nav>
        <form action="/api/greeting"><label for="name">姓名</label><input id="name" name="name"><button>生成欢迎语</button></form></body></html>
        """.formatted(HtmlUtils.htmlEscape(mode)));}

    @GetMapping("/api/health")Map<String,Object>health(){return Map.of("status","UP","mode",mode);}

    // Deterministic scope-boundary fixture: scanners must not follow this redirect.
    @GetMapping("/api/scope-redirect")ResponseEntity<Void>scopeRedirect(){return ResponseEntity.status(HttpStatus.FOUND)
            .location(java.net.URI.create("http://out-of-scope.invalid:8091/api/health")).build();}

    // Fixed destinations only: this fixture verifies that ZAP never reaches a redirected out-of-scope URL.
    @GetMapping("/api/scope-redirect/{kind}")ResponseEntity<Void>scopeRedirectMatrix(
            @PathVariable String kind,@RequestParam(defaultValue="302")int code){
        if(!Set.of(301,302,303,307,308).contains(code))return ResponseEntity.badRequest().build();
        String location=switch(kind){
            case "inside"->"/api/redirect-probe/hit";
            case "path"->"/redirect-probe-outside";
            case "host"->"http://scope-alias:8091/api/redirect-probe/hit";
            case "port"->"http://campus-demo:8092/api/redirect-probe/hit";
            case "scheme"->"https://campus-demo:8091/api/redirect-probe/hit";
            default->null;
        };
        if(location==null)return ResponseEntity.notFound().build();
        return ResponseEntity.status(code).location(java.net.URI.create(location)).build();
    }
    @GetMapping("/api/redirect-probe/hit")Map<String,Integer>insideRedirectProbe(){
        return Map.of("hits",insideRedirectProbe.incrementAndGet());
    }
    @GetMapping("/redirect-probe-outside")Map<String,Integer>outsideRedirectProbe(){
        return Map.of("hits",outsideRedirectProbe.incrementAndGet());
    }
    @GetMapping("/api/redirect-probe/counters")Map<String,Integer>redirectProbeCounters(){
        return Map.of("inside",insideRedirectProbe.get(),"outside",outsideRedirectProbe.get());
    }

    @GetMapping("/api/unavailable")ResponseEntity<Map<String,Object>>unavailable(){return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("error","controlled load-shedding fixture"));}

    @GetMapping("/api/applications/search")List<Map<String,Object>>search(@RequestParam(defaultValue="")String keyword){
        if(defective("injection"))return jdbc.queryForList("SELECT id,owner,student_name,course FROM demo_applications WHERE student_name LIKE '%"+keyword+"%'");
        return jdbc.queryForList("SELECT id,owner,student_name,course FROM demo_applications WHERE student_name LIKE ?","%"+keyword+"%");
    }

    @GetMapping(value="/api/greeting",produces=MediaType.TEXT_HTML_VALUE)String greeting(@RequestParam String name){return "<h1>欢迎，"+(defective("xss")?name:HtmlUtils.htmlEscape(name))+"</h1>";}

    @GetMapping("/api/admin/stats")ResponseEntity<?>admin(@RequestHeader HttpHeaders headers){
        Principal principal=principal(headers);
        if(principal==null)return ResponseEntity.status(401).body(Map.of("error","login required"));
        if(!defective("authorization")&&!principal.admin())return ResponseEntity.status(403).body(Map.of("error","admin required"));
        return ResponseEntity.ok(Map.of("applications",jdbc.queryForObject("SELECT COUNT(*) FROM demo_applications",Integer.class),"internalNote","仅管理员可见"));
    }

    @GetMapping("/api/applications/{id}")ResponseEntity<?>application(@PathVariable long id,@RequestHeader HttpHeaders headers){
        Principal principal=principal(headers);
        if(principal==null)return ResponseEntity.status(401).body(Map.of("error","login required"));
        var rows=jdbc.queryForList("SELECT id,owner,student_name,course FROM demo_applications WHERE id=?",id);if(rows.isEmpty())return ResponseEntity.notFound().build();
        if(!defective("authorization")&&!principal.admin()&&!principal.name().equals(rows.getFirst().get("owner")))return ResponseEntity.status(403).body(Map.of("error","not owner"));return ResponseEntity.ok(rows.getFirst());
    }

    @PostMapping(value="/api/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)ResponseEntity<?>upload(@RequestPart("file")MultipartFile file,@RequestHeader HttpHeaders headers)throws Exception{
        Principal principal=principal(headers);
        if(principal==null)return ResponseEntity.status(401).body(Map.of("error","login required"));String original=Objects.requireNonNullElse(file.getOriginalFilename(),"upload.bin");byte[]bytes=file.getBytes();
        boolean valid=original.toLowerCase(Locale.ROOT).endsWith(".txt")&&MediaType.TEXT_PLAIN_VALUE.equalsIgnoreCase(Objects.toString(file.getContentType(),""))&&bytes.length<=64_000;
        if(!defective("upload")&&!valid)return ResponseEntity.badRequest().body(Map.of("error","only small text files are allowed"));String id=UUID.randomUUID().toString();Path path=uploads.resolve(id+"-"+original.replaceAll("[^A-Za-z0-9._-]","_")).normalize();if(!path.startsWith(uploads))return ResponseEntity.badRequest().build();Files.write(path,bytes,StandardOpenOption.CREATE_NEW);jdbc.update("INSERT INTO demo_uploads(id,owner,path,original_name,content_type) VALUES (?,?,?,?,?)",id,principal.name(),path.toString(),original,Objects.toString(file.getContentType(),"application/octet-stream"));return ResponseEntity.ok(Map.of("id",id,"url","/api/uploads/"+id));
    }

    @GetMapping("/api/uploads/{id}")ResponseEntity<byte[]>download(@PathVariable String id,@RequestHeader HttpHeaders headers)throws Exception{var rows=jdbc.queryForList("SELECT * FROM demo_uploads WHERE id=?",id);if(rows.isEmpty())return ResponseEntity.notFound().build();Map<String,Object>row=rows.getFirst();Principal principal=principal(headers);if(!defective("upload")&&(principal==null||!principal.admin()&&!Objects.equals(principal.name(),row.get("owner"))))return ResponseEntity.status(403).build();byte[]bytes=Files.readAllBytes(Path.of(String.valueOf(row.get("path"))));MediaType type=defective("upload")?MediaType.TEXT_HTML:MediaType.APPLICATION_OCTET_STREAM;return ResponseEntity.ok().contentType(type).header(HttpHeaders.CONTENT_DISPOSITION,defective("upload")?"inline":"attachment").body(bytes);}

    @DeleteMapping("/api/uploads/{id}")ResponseEntity<?>deleteUpload(@PathVariable String id,@RequestHeader HttpHeaders headers)throws Exception{var rows=jdbc.queryForList("SELECT * FROM demo_uploads WHERE id=?",id);if(rows.isEmpty())return ResponseEntity.notFound().build();Principal principal=principal(headers);if(principal==null||!principal.admin()&&!Objects.equals(principal.name(),rows.getFirst().get("owner")))return ResponseEntity.status(403).build();Files.deleteIfExists(Path.of(String.valueOf(rows.getFirst().get("path"))));jdbc.update("DELETE FROM demo_uploads WHERE id=?",id);return ResponseEntity.noContent().build();}

    @GetMapping("/api/slow")Map<String,Object>slow()throws Exception{long wait=defective("performance")?450:20;Thread.sleep(wait);return Map.of("ok",true,"simulatedWorkMs",wait);}

    @PostMapping("/api/reset")ResponseEntity<?>reset(@RequestHeader HttpHeaders headers)throws Exception{Principal principal=principal(headers);if(principal==null||!principal.admin())return ResponseEntity.status(403).build();for(var row:jdbc.queryForList("SELECT path FROM demo_uploads"))Files.deleteIfExists(Path.of(String.valueOf(row.get("path"))));jdbc.update("DELETE FROM demo_uploads");jdbc.update("DELETE FROM demo_applications");jdbc.update("INSERT INTO demo_applications(id,owner,student_name,course) VALUES (1,'user-a','张同学','AI 入门'),(2,'user-b','李同学','Web 安全')");return ResponseEntity.noContent().build();}

    private Principal principal(HttpHeaders headers){
        String token=Objects.toString(headers.getFirst(HttpHeaders.AUTHORIZATION),"");
        if(token.startsWith("Bearer "))token=token.substring(7).strip();
        else {
            String cookie=Objects.toString(headers.getFirst(HttpHeaders.COOKIE),"");
            token=Arrays.stream(cookie.split(";" )).map(String::strip).filter(part->part.startsWith("demo_session="))
                    .map(part->part.substring("demo_session=".length())).findFirst().orElse("");
        }
        return switch(token){case "demo-user-a"->new Principal("user-a",false);case "demo-user-b"->new Principal("user-b",false);case "demo-admin"->new Principal("admin",true);default->null;};
    }
    private record Principal(String name,boolean admin){}

    private boolean defective(String area){if("defective".equals(mode))return true;return "regression".equals(mode)&&"authorization".equals(area);}
}
