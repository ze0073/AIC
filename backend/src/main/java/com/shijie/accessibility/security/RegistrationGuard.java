package com.shijie.accessibility.security;

import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.common.Hashes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RegistrationGuard {
    private final SecureRandom random=new SecureRandom();
    private final Map<String,Window> windows=new ConcurrentHashMap<>();
    public Map<String,String> challenge(HttpServletRequest request) {
        limit(request.getRemoteAddr()+":captcha",40,60000);
        String alphabet="23456789ABCDEFGHJKMNPQRSTUVWXYZ",answer="";
        for(int i=0;i<5;i++) answer+=alphabet.charAt(random.nextInt(alphabet.length()));
        String id=UUID.randomUUID().toString();
        request.getSession().setAttribute("registrationCaptcha",new Challenge(id,Hashes.sha256(id+answer),System.currentTimeMillis()+300000));
        try {
            BufferedImage bitmap=new BufferedImage(160,52,BufferedImage.TYPE_INT_RGB);
            Graphics2D g=bitmap.createGraphics();g.setColor(new Color(239,248,245));g.fillRect(0,0,160,52);
            g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,27));
            for(int i=0;i<5;i++){g.setColor(new Color(25+random.nextInt(80),60+random.nextInt(80),70+random.nextInt(80)));g.drawString(answer.substring(i,i+1),10+28*i,35+random.nextInt(7));}
            for(int i=0;i<10;i++){g.setColor(new Color(random.nextInt(200),random.nextInt(200),random.nextInt(200)));g.drawLine(random.nextInt(160),random.nextInt(52),random.nextInt(160),random.nextInt(52));}g.dispose();
            var bytes=new ByteArrayOutputStream();ImageIO.write(bitmap,"png",bytes);
            return Map.of("id",id,"image","data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray()));
        } catch(Exception e){throw new IllegalStateException("验证码生成失败",e);}
    }
    public void verify(HttpServletRequest request,String id,String answer) {
        limit(request.getRemoteAddr()+":register",10,3600000);
        var session=request.getSession(false);
        Object saved=session==null?null:session.getAttribute("registrationCaptcha");
        if(session!=null)session.removeAttribute("registrationCaptcha");
        if(!(saved instanceof Challenge c)||c.expires<System.currentTimeMillis()||!c.id.equals(id)||answer==null||!c.digest.equals(Hashes.sha256(id+answer.strip().toUpperCase(Locale.ROOT))))
            throw ApiException.badRequest("CAPTCHA_INVALID","验证码不正确或已过期，请刷新后重试");
    }
    public void limit(String key,int maximum,long duration) {
        long now=System.currentTimeMillis();
        if(windows.size()>20000) windows.entrySet().removeIf(e->e.getValue().until<now);
        Window w=windows.compute(key,(k,old)->old==null||old.until<now?new Window(now+duration):old);
        synchronized(w){if(++w.count>maximum)throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","尝试过于频繁，请稍后重试");}
    }
    record Challenge(String id,String digest,long expires){}
    static class Window {final long until;int count;Window(long until){this.until=until;}}
}
