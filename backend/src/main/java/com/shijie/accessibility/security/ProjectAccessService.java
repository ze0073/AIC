package com.shijie.accessibility.security;

import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.model.ProjectEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class ProjectAccessService {
    private static final List<String> ROLE_ORDER = List.of("VIEWER", "MEMBER", "ADMIN");
    private final JdbcTemplate jdbc;
    private final AuthService auth;

    public ProjectAccessService(JdbcTemplate jdbc, AuthService auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    public AuthService.UserView currentUser() { return auth.current(); }

    public void addOwner(String projectId) {
        jdbc.update("INSERT INTO project_members(project_id,user_id,role,created_at) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE role='ADMIN'",
                projectId, auth.current().id(), "ADMIN", LocalDateTime.now());
    }

    @org.springframework.transaction.annotation.Transactional
    public void addMember(String projectId, String userId, String role) {
        jdbc.queryForList("SELECT id FROM app_users ORDER BY id FOR UPDATE");
        require(projectId, "ADMIN");
        String normalized = role == null ? "VIEWER" : role.toUpperCase();
        if (!ROLE_ORDER.contains(normalized)) throw ApiException.badRequest("INVALID_ROLE", "成员角色无效");
        Integer enabled=jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE id=? AND enabled=TRUE",Integer.class,userId);
        if(enabled==null||enabled==0)throw ApiException.notFound("账号不存在或已停用");
        if(!"ADMIN".equals(normalized))requireOtherAdministrator(projectId,userId);
        jdbc.update("INSERT INTO project_members(project_id,user_id,role,created_at) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE role=VALUES(role)",
                projectId, userId, normalized, LocalDateTime.now());
    }
    @org.springframework.transaction.annotation.Transactional
    public void invite(String projectId,String username,String role){require(projectId,"ADMIN");var users=jdbc.queryForList("SELECT id FROM app_users WHERE username=? AND enabled=TRUE",username.strip().toLowerCase(java.util.Locale.ROOT));if(users.isEmpty())throw ApiException.notFound("账号尚未注册，请让对方先创建账号");addMember(projectId,(String)users.getFirst().get("id"),role);}

    public void require(String projectId, String minimumRole) {
        String userId = auth.current().id();
        List<String> roles = jdbc.query("SELECT role FROM project_members WHERE project_id=? AND user_id=?",
                (rs, n) -> rs.getString(1), projectId, userId);
        if (roles.isEmpty() || rank(roles.getFirst()) < rank(minimumRole)) {
            throw ApiException.forbidden("无权访问该项目");
        }
        if (!"VIEWER".equals(minimumRole)) {
            requireMutable(projectId);
        }
    }

    public void requireMutable(String projectId) {
        Integer cleaning=jdbc.queryForObject("SELECT COUNT(*) FROM projects WHERE id=? AND deletion_status<>'ACTIVE'",Integer.class,projectId);
        if(cleaning!=null&&cleaning>0)throw ApiException.conflict("PROJECT_DELETING","项目正在清理或等待重试，不能修改任务");
        // The deletion request takes this same lock before marking the site, so queued work cannot race it.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            var projects=jdbc.queryForList("SELECT deletion_status FROM projects WHERE id=? FOR UPDATE",projectId);
            if(projects.stream().anyMatch(project->!"ACTIVE".equals(project.get("deletion_status"))))
                throw ApiException.conflict("PROJECT_DELETING","项目正在清理或等待重试，不能修改任务");
            // A locking read sees the latest deletion marker even if this transaction already has an older snapshot.
            var sites=jdbc.queryForList("SELECT deletion_status FROM hosted_sites WHERE project_id=? FOR UPDATE",projectId);
            if (sites.stream().anyMatch(site->!"ACTIVE".equals(site.get("deletion_status"))))
                throw ApiException.conflict("SITE_DELETING","网站正在删除或等待重试，不能启动或修改任务");
            return;
        }
        Integer deleting=jdbc.queryForObject("SELECT COUNT(*) FROM hosted_sites WHERE project_id=? AND deletion_status<>'ACTIVE'",Integer.class,projectId);
        if (deleting!=null && deleting>0) throw ApiException.conflict("SITE_DELETING","网站正在删除或等待重试，不能启动或修改任务");
    }

    public List<ProjectEntity> visibleProjects(List<ProjectEntity> projects) {
        Set<String> ids = Set.copyOf(jdbc.query("SELECT project_id FROM project_members WHERE user_id=?",
                (rs, n) -> rs.getString(1), auth.current().id()));
        return projects.stream().filter(project -> ids.contains(project.id)).peek(project->project.projectRole=currentRole(project.id)).toList();
    }
    public String currentRole(String project){return jdbc.queryForObject("SELECT role FROM project_members WHERE project_id=? AND user_id=?",String.class,project,auth.current().id());}

    public List<MemberView> members(String projectId) {
        require(projectId, "VIEWER");
        return jdbc.query("SELECT u.id,u.username,u.display_name,m.role,m.created_at FROM project_members m JOIN app_users u ON u.id=m.user_id WHERE m.project_id=? ORDER BY m.created_at",
                (rs, n) -> new MemberView(rs.getString("id"), rs.getString("username"), rs.getString("display_name"),
                        rs.getString("role"), rs.getTimestamp("created_at").toLocalDateTime()), projectId);
    }

    private int rank(String role) {
        int rank = ROLE_ORDER.indexOf(role == null ? "" : role.toUpperCase());
        return rank < 0 ? -1 : rank;
    }

    @org.springframework.transaction.annotation.Transactional
    public void removeMember(String projectId,String userId){
        jdbc.queryForList("SELECT id FROM app_users ORDER BY id FOR UPDATE");
        require(projectId,"ADMIN");requireOtherAdministrator(projectId,userId);
        jdbc.update("DELETE FROM project_members WHERE project_id=? AND user_id=?",projectId,userId);
    }
    private void requireOtherAdministrator(String projectId,String userId){
        Integer admin=jdbc.queryForObject("SELECT COUNT(*) FROM project_members WHERE project_id=? AND user_id=? AND role='ADMIN'",Integer.class,projectId,userId);
        if(admin!=null&&admin>0){Integer others=jdbc.queryForObject("SELECT COUNT(*) FROM project_members m JOIN app_users u ON u.id=m.user_id WHERE m.project_id=? AND m.user_id<>? AND m.role='ADMIN' AND u.enabled=TRUE",Integer.class,projectId,userId);if(others==null||others==0)throw ApiException.conflict("LAST_PROJECT_ADMIN","项目必须保留一个启用的管理员");}
    }
    public void requireActor(String project,String actor){
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM project_members m JOIN app_users u ON u.id=m.user_id JOIN projects p ON p.id=m.project_id WHERE m.project_id=? AND m.user_id=? AND m.role IN ('MEMBER','ADMIN') AND u.enabled=TRUE AND p.deletion_status='ACTIVE'",Integer.class,project,actor);
        if(count==null||count==0)throw ApiException.forbidden("执行者已无项目操作权限");
        requireMutable(project);
    }
    public long version(String user){Long value=jdbc.queryForObject("SELECT auth_version FROM app_users WHERE id=?",Long.class,user);return value==null?-1:value;}
    public boolean canRead(String project,String user,long version){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM project_members m JOIN app_users u ON u.id=m.user_id WHERE m.project_id=? AND m.user_id=? AND u.enabled=TRUE AND u.auth_version=?",Integer.class,project,user,version);return count!=null&&count>0;}

    public record MemberView(String userId, String username, String displayName, String role, LocalDateTime createdAt) {}
}
