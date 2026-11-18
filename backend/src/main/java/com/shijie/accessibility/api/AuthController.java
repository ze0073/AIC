package com.shijie.accessibility.api;

import com.shijie.accessibility.security.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthenticationManager authentication;
    private final SecurityContextRepository contexts;
    private final AuthService users;
    @org.springframework.beans.factory.annotation.Autowired private com.shijie.accessibility.security.RegistrationGuard registration;

    @GetMapping("/auth/captcha") Map<String,String> captcha(HttpServletRequest request) { return registration.challenge(request); }
    @PostMapping("/auth/register") AuthService.UserView register(@Valid @RequestBody RegisterRequest body,HttpServletRequest request,HttpServletResponse response) {
        registration.verify(request,body.captchaId(),body.captcha());
        users.register(body.username(),body.displayName(),body.password());
        return login(new LoginRequest(body.username(),body.password()),request,response);
    }
    @GetMapping("/auth/quota") Map<String,Object> quota(){return users.quota(users.current().id());}
    @GetMapping("/users/{id}/quota") Map<String,Object> quota(@PathVariable String id){return users.quota(id);}
    @PostMapping("/users/{id}/quota") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) void quota(@PathVariable String id,@RequestBody Quota body){users.setQuota(id,body.projectLimit(),body.environmentLimit(),body.buildLimit());}
    public record RegisterRequest(@NotBlank @Size(max=80) String username,@NotBlank @Size(max=120) String displayName,@NotBlank @Size(min=10,max=200) String password,@NotBlank String captchaId,@NotBlank @Size(max=10) String captcha){}
    public record Quota(int projectLimit,int environmentLimit,int buildLimit){}

    public AuthController(AuthenticationManager authentication, SecurityContextRepository contexts, AuthService users) {
        this.authentication = authentication;
        this.contexts = contexts;
        this.users = users;
    }

    @GetMapping("/auth/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/auth/login")
    AuthService.UserView login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        registration.limit(request.getRemoteAddr()+":login",30,60000);
        var result = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        var context = SecurityContextHolder.createEmptyContext();
        if(request.getSession(false)!=null) request.changeSessionId();
        context.setAuthentication(result);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        request.getSession().setAttribute(com.shijie.accessibility.security.SessionVersionFilter.VERSION,users.authVersion(result.getName()));
        return users.current();
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
    }

    @PostMapping("/auth/password")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void changePassword(@Valid @RequestBody ChangePasswordRequest body) {
        users.changeOwnPassword(body.currentPassword(),body.newPassword());
    }

    @GetMapping("/auth/me")
    AuthService.UserView me() { return users.current(); }

    @GetMapping("/users")
    List<AuthService.UserView> listUsers() { users.current(); return users.list(); }

    @PostMapping("/users")
    AuthService.UserView createUser(@Valid @RequestBody CreateUserRequest body) {
        return users.create(body.username(), body.displayName(), body.password());
    }

    @PostMapping("/users/{id}/status") AuthService.UserView status(@PathVariable String id,@RequestBody UserStatus body){return users.setEnabled(id,body.enabled());}
    @PostMapping("/users/{id}/password") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void resetPassword(@PathVariable String id,@Valid @RequestBody ResetPassword body){users.resetPassword(id,body.newPassword());}
    public record UserStatus(boolean enabled){}
    public record ResetPassword(@NotBlank @Size(min=10,max=200) String newPassword){}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min=10,max=200) String newPassword) {}
    public record CreateUserRequest(@NotBlank @Size(max=80) String username,
                                    @NotBlank @Size(max=120) String displayName,
                                    @NotBlank @Size(min=10,max=200) String password) {}
}
