package com.shijie.accessibility.service;

import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.common.Ulids;
import com.shijie.accessibility.security.ProjectAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class TestSuiteService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProjectAccessService access;
    private final IdempotencyService idempotency;

    public TestSuiteService(JdbcTemplate jdbc, ObjectMapper json, ProjectAccessService access, IdempotencyService idempotency) {
        this.jdbc = jdbc; this.json = json; this.access = access; this.idempotency = idempotency;
    }

    @Transactional
    public SuiteView create(String projectId, String name, String description, Map<String, Object> definition) {
        access.require(projectId, "MEMBER");
        validateDefinition(definition);
        String suiteId = Ulids.next();
        String versionId = Ulids.next();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO test_suites(id,project_id,name,description,current_version,created_at,updated_at) VALUES (?,?,?,?,1,?,?)",
                suiteId, projectId, name.strip(), clean(description), now, now);
        jdbc.update("INSERT INTO test_suite_versions(id,suite_id,version_no,definition,created_by,created_at) VALUES (?,?,?,?,?,?)",
                versionId, suiteId, 1, write(definition), access.currentUser().id(), now);
        return get(projectId, suiteId);
    }

    @Transactional
    public SuiteVersion publish(String suiteId, Map<String, Object> definition, String key) {
        var previous = idempotency.find(key, "publish-test-suite-version");
        if (previous.isPresent()) return version(previous.get());
        SuiteView suite = getById(suiteId);
        access.require(suite.projectId(), "MEMBER");
        validateDefinition(definition);
        int version = suite.currentVersion() + 1;
        String id = Ulids.next();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO test_suite_versions(id,suite_id,version_no,definition,created_by,created_at) VALUES (?,?,?,?,?,?)",
                id, suiteId, version, write(definition), access.currentUser().id(), now);
        jdbc.update("UPDATE test_suites SET current_version=?,updated_at=? WHERE id=?", version, now, suiteId);
        idempotency.save(key, "publish-test-suite-version", id);
        return version(id);
    }

    public List<SuiteView> list(String projectId) {
        access.require(projectId, "VIEWER");
        return jdbc.query("SELECT s.*,(SELECT v.id FROM test_suite_versions v WHERE v.suite_id=s.id AND v.version_no=s.current_version) current_version_id FROM test_suites s WHERE s.project_id=? ORDER BY s.updated_at DESC", this::mapSuite, projectId);
    }

    public SuiteView get(String projectId, String id) {
        SuiteView result = getById(id);
        if (!result.projectId().equals(projectId)) throw ApiException.notFound("测试套件不存在");
        access.require(projectId, "VIEWER");
        return result;
    }

    public SuiteVersion version(String id) {
        SuiteVersion result=systemVersion(id);
        access.require(result.projectId(), "VIEWER");
        return result;
    }

    public SuiteVersion systemVersion(String id) {
        List<SuiteVersion> rows = jdbc.query("SELECT v.*,s.project_id FROM test_suite_versions v JOIN test_suites s ON s.id=v.suite_id WHERE v.id=?",
                (rs, n) -> new SuiteVersion(rs.getString("id"), rs.getString("suite_id"), rs.getString("project_id"),
                        rs.getInt("version_no"), read(rs.getString("definition")), rs.getTimestamp("created_at").toLocalDateTime()), id);
        if (rows.isEmpty()) throw ApiException.notFound("测试套件版本不存在");
        return rows.getFirst();
    }

    private SuiteView getById(String id) {
        List<SuiteView> rows = jdbc.query("SELECT s.*,(SELECT v.id FROM test_suite_versions v WHERE v.suite_id=s.id AND v.version_no=s.current_version) current_version_id FROM test_suites s WHERE s.id=?", this::mapSuite, id);
        if (rows.isEmpty()) throw ApiException.notFound("测试套件不存在");
        return rows.getFirst();
    }

    private SuiteView mapSuite(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new SuiteView(rs.getString("id"), rs.getString("project_id"), rs.getString("name"), rs.getString("description"),
                rs.getInt("current_version"), rs.getString("current_version_id"), rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime());
    }

    void validateDefinition(Map<String, Object> definition) {
        if (definition == null) throw ApiException.badRequest("SUITE_DEFINITION_REQUIRED", "测试套件定义不能为空");
        if (size(definition.get("pages")) > 10) throw ApiException.badRequest("PAGE_LIMIT_EXCEEDED", "在线页面最多 10 个");
        if (size(definition.get("flowSteps")) > 20) throw ApiException.badRequest("FLOW_STEP_LIMIT_EXCEEDED", "流程用例最多 20 步");
        if (size(definition.get("apiCases")) > 10) throw ApiException.badRequest("API_CASE_LIMIT_EXCEEDED", "主动检测最多登记 10 个接口");
        Object performance = definition.get("performance");
        if (performance instanceof Map<?, ?> config) {
            numberAtMost(config.get("maxVus"), 20, "虚拟用户最多 20 个");
            numberAtMost(config.get("durationSeconds"), 120, "负载测试最多 120 秒");
            numberInRange(config.get("stageDurationSeconds"),1,40,"每阶段时长必须为 1–40 秒");
            numberInRange(config.get("ratePerSecond"),1,50,"负载速率必须为每秒 1–50 次请求");
            numberInRange(config.get("p95Ms"),50,60_000,"P95 阈值必须为 50–60000 毫秒");
            numberInRange(config.get("errorRate"),0.0001,0.5,"错误率阈值必须大于 0 且不超过 0.5");
            Object stages=config.get("vus");
            if(stages!=null){
                if(!(stages instanceof List<?> values)||values.isEmpty()||values.size()>3)
                    throw ApiException.badRequest("SUITE_LIMIT_EXCEEDED","负载阶段必须为 1–3 个");
                for(Object value:values)numberInRange(value,1,20,"每阶段虚拟用户必须为 1–20 个");
            }
        }
    }

    private int size(Object value) { return value instanceof List<?> list ? list.size() : 0; }
    private void numberAtMost(Object value, int max, String message) {
        if (value instanceof Number n && n.doubleValue() > max) throw ApiException.badRequest("SUITE_LIMIT_EXCEEDED", message);
    }
    private void numberInRange(Object value,double min,double max,String message){
        if(value==null)return;
        if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<min||n.doubleValue()>max)
            throw ApiException.badRequest("SUITE_LIMIT_EXCEEDED",message);
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    @SuppressWarnings("unchecked") private Map<String, Object> read(String value) { try { return (Map<String, Object>) json.readValue(value, Map.class); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.strip(); }

    public record SuiteView(String id, String projectId, String name, String description, int currentVersion, String currentVersionId,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record SuiteVersion(String id, String suiteId, String projectId, int versionNo,
                               Map<String, Object> definition, LocalDateTime createdAt) {}
}
