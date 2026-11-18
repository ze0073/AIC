package com.shijie.accessibility.service;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Serialize same-account keys on this local server, and fingerprint the actual request. */
public class IdempotencyRequestFilter extends OncePerRequestFilter {
    public static final String HASH="shijie.request.hash";
    private final ReentrantLock[] locks=new ReentrantLock[128];
    public IdempotencyRequestFilter(){Arrays.setAll(locks,i->new ReentrantLock());}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
        String key=req.getHeader("Idempotency-Key");
        if(key==null||!req.getRequestURI().startsWith("/api/")){chain.doFilter(req,res);return;}
        if(key.isBlank()||key.length()>120){res.sendError(400,"Invalid Idempotency-Key");return;}
        var auth=SecurityContextHolder.getContext().getAuthentication();
        String actor=auth==null?"SYSTEM":auth.getName();
        ReentrantLock lock=locks[Math.floorMod(Objects.hash(actor,key),locks.length)];
        lock.lock();
        try {
            MessageDigest digest;
            try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
            digest.update((req.getMethod()+" "+req.getRequestURI()+"?"+Objects.toString(req.getQueryString(),"")+"\n").getBytes(StandardCharsets.UTF_8));
            HttpServletRequest wrapped=req;
            if(Objects.toString(req.getContentType(),"").startsWith("multipart/")) {
                var parts=new ArrayList<>(req.getParts());parts.sort(Comparator.comparing(Part::getName));
                for(var part:parts){digest.update((part.getName()+"\n"+part.getSubmittedFileName()+"\n"+part.getContentType()+"\n").getBytes(StandardCharsets.UTF_8));try(var in=part.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))>=0)digest.update(buffer,0,n);}}
            }else{
                byte[] body=req.getInputStream().readNBytes(21*1024*1024);
                if(body.length>20*1024*1024){res.sendError(413);return;}
                digest.update(body);wrapped=new BodyRequest(req,body);
            }
            wrapped.setAttribute(HASH,HexFormat.of().formatHex(digest.digest()));
            chain.doFilter(wrapped,res);
        }finally{lock.unlock();}
    }
    private static class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BodyRequest(HttpServletRequest request,byte[] body){super(request);this.body=body;}
        @Override public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(body);return new ServletInputStream(){public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous request body");}};}
        @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
    }
}
