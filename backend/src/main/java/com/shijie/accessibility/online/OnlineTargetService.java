package com.shijie.accessibility.online;

import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.common.Ulids;
import com.shijie.accessibility.security.ProjectAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OnlineTargetService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TargetScopeService scope;
    private final CredentialCipher cipher;
    private final ProjectAccessService access;

    public OnlineTargetService(JdbcTemplate jdbc, ObjectMapper json, TargetScopeService scope,
                               CredentialCipher cipher, ProjectAccessService access) {
        this.jdbc = jdbc; this.json = json; this.scope = scope; this.cipher = cipher; this.access = access;
    }

    @Transactional
    public TargetView create(String projectId, CreateTarget request) {
        access.require(projectId, "ADMIN");
        TargetScopeService.ValidatedTarget checked = scope.validateRegistration(request.baseUrl(), request.allowedAddresses(), request.pathPrefixes());
        String id = Ulids.next();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO online_targets(id,project_id,name,base_url,allowed_origin,allowed_addresses,path_prefixes,allow_active_scan,allow_write_tests,cleanup_instructions,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, projectId, request.name().strip(), checked.baseUrl(), checked.origin(), write(checked.addresses()), write(checked.pathPrefixes()),
                request.allowActiveScan(), request.allowWriteTests(), clean(request.cleanupInstructions()), now, now);
        return get(projectId, id);
    }

    public List<TargetView> list(String projectId) {
        access.require(projectId, "VIEWER");
        return jdbc.query("SELECT * FROM online_targets WHERE project_id=? ORDER BY updated_at DESC", this::mapTarget, projectId);
    }

    public TargetScopeService.ValidatedTarget preview(String projectId, String baseUrl) {
        access.require(projectId, "ADMIN");
        return scope.previewRegistration(baseUrl);
    }

    public TargetView get(String projectId, String id) {
        access.require(projectId, "VIEWER");
        return systemGet(projectId, id);
    }

    /** Internal execution lookup; callers must have authorized the run's project before scheduling it. */
    public TargetView systemGet(String projectId, String id) {
        List<TargetView> rows = jdbc.query("SELECT * FROM online_targets WHERE id=? AND project_id=?", this::mapTarget, id, projectId);
        if (rows.isEmpty()) throw ApiException.notFound("在线目标不存在");
        return rows.getFirst();
    }

    @Transactional
    public CredentialView createCredential(String projectId, CreateCredential request) {
        access.require(projectId, "ADMIN");
        TargetView target = get(projectId, request.targetId());
        String principal = request.principalType().toUpperCase();
        String authType = request.authType().toUpperCase();
        if (!List.of("ANONYMOUS","USER_A","USER_B","ADMIN").contains(principal))
            throw ApiException.badRequest("INVALID_PRINCIPAL_TYPE", "凭据身份类型无效");
        if (!List.of("BEARER","COOKIE").contains(authType))
            throw ApiException.badRequest("INVALID_AUTH_TYPE", "仅支持 Bearer Token 和 Cookie");
        String id = Ulids.next();
        LocalDateTime now = LocalDateTime.now();
        String binding = projectId + ":" + target.id() + ":" + id + ":" + target.allowedOrigin();
        jdbc.update("INSERT INTO encrypted_credentials(id,project_id,target_id,name,principal_type,auth_type,encrypted_secret,secret_fingerprint,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, projectId, target.id(), request.name().strip(), principal, authType,
                cipher.encrypt(request.secret(), binding), cipher.fingerprint(request.secret()), now, now);
        return credential(projectId, id);
    }

    public List<CredentialView> credentials(String projectId) {
        access.require(projectId, "VIEWER");
        return jdbc.query("SELECT id,project_id,target_id,name,principal_type,auth_type,secret_fingerprint,created_at,updated_at FROM encrypted_credentials WHERE project_id=? ORDER BY updated_at DESC",
                this::mapCredential, projectId);
    }

    public SecretCredential secret(String projectId, String id) {
        access.require(projectId, "MEMBER");
        return systemSecret(projectId, id);
    }

    /** Internal execution lookup; callers must have authorized and snapshotted the run first. */
    public SecretCredential systemSecret(String projectId, String id) {
        List<SecretCredential> rows = jdbc.query("SELECT c.*,t.allowed_origin FROM encrypted_credentials c JOIN online_targets t ON t.id=c.target_id WHERE c.id=? AND c.project_id=?",
                (rs, n) -> {
                    String binding = projectId + ":" + rs.getString("target_id") + ":" + rs.getString("id") + ":" + rs.getString("allowed_origin");
                    return new SecretCredential(rs.getString("id"), rs.getString("target_id"), rs.getString("principal_type"),
                            rs.getString("auth_type"), cipher.decrypt(rs.getString("encrypted_secret"), binding), rs.getString("allowed_origin"));
                }, id, projectId);
        if (rows.isEmpty()) throw ApiException.notFound("凭据不存在");
        return rows.getFirst();
    }

    private TargetView mapTarget(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new TargetView(rs.getString("id"), rs.getString("project_id"), rs.getString("name"), rs.getString("base_url"),
                rs.getString("allowed_origin"), readList(rs.getString("allowed_addresses")), readList(rs.getString("path_prefixes")),
                rs.getBoolean("allow_active_scan"), rs.getBoolean("allow_write_tests"), rs.getString("cleanup_instructions"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private CredentialView credential(String projectId, String id) {
        List<CredentialView> rows = jdbc.query("SELECT id,project_id,target_id,name,principal_type,auth_type,secret_fingerprint,created_at,updated_at FROM encrypted_credentials WHERE id=? AND project_id=?",
                this::mapCredential, id, projectId);
        if (rows.isEmpty()) throw ApiException.notFound("凭据不存在");
        return rows.getFirst();
    }

    private CredentialView mapCredential(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new CredentialView(rs.getString("id"), rs.getString("project_id"), rs.getString("target_id"), rs.getString("name"),
                rs.getString("principal_type"), rs.getString("auth_type"), rs.getString("secret_fingerprint").substring(0, 12),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    @SuppressWarnings("unchecked")
    private List<String> readList(String value) { try { return (List<String>) (List<?>) json.readValue(value, List.class); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.strip(); }

    public record CreateTarget(String name, String baseUrl, List<String> allowedAddresses, List<String> pathPrefixes,
                               boolean allowActiveScan, boolean allowWriteTests, String cleanupInstructions) {}
    public record TargetView(String id, String projectId, String name, String baseUrl, String allowedOrigin,
                             List<String> allowedAddresses, List<String> pathPrefixes, boolean allowActiveScan,
                             boolean allowWriteTests, String cleanupInstructions, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record CreateCredential(String targetId, String name, String principalType, String authType, String secret) {}
    public record CredentialView(String id, String projectId, String targetId, String name, String principalType,
                                 String authType, String fingerprintPrefix, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record SecretCredential(String id, String targetId, String principalType, String authType, String secret, String allowedOrigin) {}
}
