package com.shijie.accessibility.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Database-backed revocation also covers API methods which do not call current(). */
public class SessionVersionFilter extends OncePerRequestFilter {
    public static final String VERSION="shijie.auth.version";
    private final AuthService users;
    public SessionVersionFilter(AuthService users){this.users=users;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(req.getRequestURI().startsWith("/api/")&&auth!=null&&auth.isAuthenticated()&&!"anonymousUser".equals(auth.getPrincipal())) {
            var session=req.getSession(false);
            Object version=session==null?null:session.getAttribute(VERSION);
            if(!(version instanceof Number number)||!users.validSession(auth.getName(),number.longValue())) {
                if(session!=null)session.invalidate();
                SecurityContextHolder.clearContext();
                res.setStatus(401);res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"code\":\"SESSION_REVOKED\",\"message\":\"登录已失效，请重新登录\"}");return;
            }
        }
        chain.doFilter(req,res);
    }
}
