package com.shijie.fixture;
import org.springframework.boot.*;import org.springframework.boot.autoconfigure.*;import org.springframework.web.bind.annotation.*;
@SpringBootApplication @RestController public class Application {
 public static void main(String[] args){SpringApplication.run(Application.class,args);}
 @GetMapping(value="/",produces="text/html;charset=UTF-8") public String index(){return "<!doctype html><html lang=\"zh-CN\"><head><title>Spring Boot 托管验收</title></head><body><h1>Java 网站运行成功</h1></body></html>";}
}
