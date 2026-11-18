package com.shijie.accessibility.security;

import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.common.Ulids;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AuthService implements UserDetailsService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String initialUsername;
    private final String initialPassword;
    private final String initialDisplayName;

    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwords,
                       @Value("${app.security.initial-admin-username:admin}") String initialUsername,
                       @Value("${app.security.initial-admin-password:change-me-on-first-run}") String initialPassword,
                       @Value("${app.security.initial-admin-display-name:系统管理员}") String initialDisplayName) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.initialUsername = normalize(initialUsername);
        this.initialPassword = initialPassword;
        this.initialDisplayName = initialDisplayName.strip();
    }

    @PostConstruct
    @Transactional
    void initializeAdministrator() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM app_users", Integer.class);
        if (count != null && count == 0) {
            String id = Ulids.next();
            LocalDateTime now = LocalDateTime.now();
            jdbc.update("INSERT INTO app_users(id,username,display_name,password_hash,enabled,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                    id, initialUsername, initialDisplayName, passwords.encode(initialPassword), true, now, now);
            log.warn("Initialized the local administrator account '{}'; change APP_INITIAL_ADMIN_PASSWORD before shared deployment", initialUsername);
        }
        UserView admin = byUsername(initialUsername);
        if (admin != null) {
            jdbc.update("UPDATE app_users SET platform_role='ADMIN' WHERE id=?", admin.id());
        }
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT username,password_hash,enabled FROM app_users WHERE username=?", normalize(username));
        if (rows.isEmpty()) throw new UsernameNotFoundException("账号不存在");
        Map<String, Object> row = rows.getFirst();
        return User.withUsername(String.valueOf(row.get("username")))
                .password(String.valueOf(row.get("password_hash")))
                .disabled(!Boolean.TRUE.equals(row.get("enabled")))
                .authorities("PLATFORM_USER").build();
    }

    public UserView current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            throw ApiException.unauthorized("请先登录");
        }
        UserView user = byUsername(authentication.getName());
        if (user == null || !user.enabled()) throw ApiException.unauthorized("账号已停用");
        return user;
    }

    @Transactional
    public void changeOwnPassword(String currentPassword,String newPassword) {
        UserView user=current();
        if(newPassword==null||newPassword.length()<10||newPassword.length()>200)
            throw ApiException.badRequest("WEAK_PASSWORD","新密码长度需为 10–200 位");
        String hash=jdbc.queryForObject("SELECT password_hash FROM app_users WHERE id=?",String.class,user.id());
        if(hash==null||!passwords.matches(currentPassword,hash))
            throw ApiException.badRequest("CURRENT_PASSWORD_INVALID","当前密码不正确");
        jdbc.update("UPDATE app_users SET password_hash=?,auth_version=auth_version+1,updated_at=? WHERE id=?",passwords.encode(newPassword),LocalDateTime.now(),user.id());
    }

    public long authVersion(String username){Long version=jdbc.queryForObject("SELECT auth_version FROM app_users WHERE username=?",Long.class,normalize(username));return version==null?-1:version;}
    public boolean validSession(String username,long version){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username=? AND enabled=TRUE AND auth_version=?",Integer.class,normalize(username),version);return count!=null&&count==1;}
    @Transactional public UserView setEnabled(String id,boolean enabled){
        UserView actor=current();requirePlatformAdmin();
        // Serialize administrator/member changes in a consistent order.
        jdbc.queryForList("SELECT id FROM app_users ORDER BY id FOR UPDATE");
        var rows=jdbc.queryForList("SELECT username,platform_role FROM app_users WHERE id=?",id);
        if(rows.isEmpty())throw ApiException.notFound("账号不存在");
        if(!enabled){
            if(id.equals(actor.id()))throw ApiException.conflict("SELF_DISABLE","不能停用当前账号");
            if("ADMIN".equals(rows.getFirst().get("platform_role"))&&jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE enabled=TRUE AND platform_role='ADMIN' AND id<>?",Integer.class,id)==0)throw ApiException.conflict("LAST_PLATFORM_ADMIN","必须保留一个启用的平台管理员");
            Integer sole=jdbc.queryForObject("SELECT COUNT(*) FROM project_members m WHERE m.user_id=? AND m.role='ADMIN' AND NOT EXISTS(SELECT 1 FROM project_members other JOIN app_users u ON u.id=other.user_id WHERE other.project_id=m.project_id AND other.user_id<>? AND other.role='ADMIN' AND u.enabled=TRUE)",Integer.class,id,id);
            if(sole!=null&&sole>0)throw ApiException.conflict("LAST_PROJECT_ADMIN","请先为该账号的项目增加有效管理员");
        }
        jdbc.update("UPDATE app_users SET enabled=?,auth_version=auth_version+1,updated_at=? WHERE id=? AND enabled<>?",enabled,LocalDateTime.now(),id,enabled);
        return byUsername(rows.getFirst().get("username").toString());
    }
    @Transactional public void resetPassword(String id,String password){
        requirePlatformAdmin();
        if(password==null||password.length()<10||password.length()>200)throw ApiException.badRequest("WEAK_PASSWORD","密码长度需为 10–200 位");
        if(jdbc.update("UPDATE app_users SET password_hash=?,auth_version=auth_version+1,updated_at=? WHERE id=?",passwords.encode(password),LocalDateTime.now(),id)!=1)throw ApiException.notFound("账号不存在");
    }

    public List<UserView> list() {
        requirePlatformAdmin();
        return jdbc.query("SELECT id,username,display_name,enabled,created_at,platform_role FROM app_users ORDER BY created_at",
                (rs, n) -> new UserView(rs.getString("id"), rs.getString("username"), rs.getString("display_name"),
                        rs.getBoolean("enabled"), rs.getTimestamp("created_at").toLocalDateTime(), rs.getString("platform_role")));
    }

    @Transactional
    public UserView create(String username, String displayName, String password) {
        requirePlatformAdmin();
        return register(username, displayName, password);
    }

    @Transactional
    public UserView register(String username, String displayName, String password) {
        String normalized = normalize(username);
        if (!normalized.matches("[a-z0-9._-]{3,80}")) {
            throw ApiException.badRequest("INVALID_USERNAME", "用户名需为 3–80 位小写字母、数字、点、下划线或连字符");
        }
        if (password == null || password.length() < 10 || password.length() > 200) {
            throw ApiException.badRequest("WEAK_PASSWORD", "密码长度需为 10–200 位");
        }
        if (byUsername(normalized) != null) throw ApiException.conflict("USERNAME_EXISTS", "用户名已存在");
        String id = Ulids.next();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO app_users(id,username,display_name,password_hash,enabled,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                id, normalized, displayName.strip(), passwords.encode(password), true, now, now);
        return byUsername(normalized);
    }

    public void requirePlatformAdmin() {
        if (!"ADMIN".equals(current().platformRole())) throw ApiException.forbidden("只有平台管理员可以管理账号");
    }

    public void requireProjectQuota() {
        var user=current();
        jdbc.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE",user.id());
        Integer limit=jdbc.queryForObject("SELECT project_limit FROM app_users WHERE id=?",Integer.class,user.id());
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM project_members WHERE user_id=? AND role='ADMIN'",Integer.class,user.id());
        if(count!=null && count>=limit) throw ApiException.conflict("PROJECT_QUOTA","已达到项目额度，请删除空项目或联系平台管理员调整额度");
    }

    public Map<String,Object> quota(String userId) {
        if(!current().id().equals(userId)) requirePlatformAdmin();
        return jdbc.queryForMap("SELECT project_limit AS projectLimit,environment_limit AS environmentLimit,build_limit AS buildLimit FROM app_users WHERE id=?",userId);
    }

    public void setQuota(String id,int projects,int environments,int builds) {
        requirePlatformAdmin();
        if(projects<1||projects>100||environments<1||environments>20||builds<1||builds>4) throw ApiException.badRequest("INVALID_QUOTA","额度超出允许范围");
        jdbc.update("UPDATE app_users SET project_limit=?,environment_limit=?,build_limit=? WHERE id=?",projects,environments,builds,id);
    }

    private UserView byUsername(String username) {
        List<UserView> users = jdbc.query("SELECT id,username,display_name,enabled,created_at,platform_role FROM app_users WHERE username=?",
                (rs, n) -> new UserView(rs.getString("id"), rs.getString("username"), rs.getString("display_name"),
                        rs.getBoolean("enabled"), rs.getTimestamp("created_at").toLocalDateTime(), rs.getString("platform_role")), normalize(username));
        return users.isEmpty() ? null : users.getFirst();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    public record UserView(String id, String username, String displayName, boolean enabled, LocalDateTime createdAt, String platformRole) {
        public UserView(String id,String username,String displayName,boolean enabled,LocalDateTime createdAt){this(id,username,displayName,enabled,createdAt,"USER");}
    }
}
