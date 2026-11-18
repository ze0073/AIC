package com.shijie.accessibility.service;

import com.shijie.accessibility.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class IdempotencyService {
    private final JdbcTemplate jdbc;

    public IdempotencyService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<String> find(String key, String operation) {
        validate(key);
        return jdbc.query("SELECT resource_id,request_hash FROM scoped_idempotency_records WHERE actor=? AND idempotency_key=? AND operation=?",
                rs -> {if(!rs.next())return Optional.empty();if(!fingerprint(operation,key).equals(rs.getString(2)))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","该请求标识已经用于不同的资源或内容");return Optional.of(rs.getString(1));}, actor(), key, operation);
    }

    public void save(String key, String operation, String resourceId) {
        validate(key);
        jdbc.update("INSERT INTO scoped_idempotency_records(actor,idempotency_key,operation,request_hash,resource_id,created_at) VALUES(?,?,?,?,?,?)",
                actor(), key, operation, fingerprint(operation,key), resourceId, LocalDateTime.now());
    }

    private String actor(){var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();return auth==null||!auth.isAuthenticated()?"SYSTEM":auth.getName();}
    private String fingerprint(String operation,String key){var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();if(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes request){Object hash=request.getRequest().getAttribute(IdempotencyRequestFilter.HASH);if(hash!=null)return hash.toString();}return com.shijie.accessibility.common.Hashes.sha256(operation+":"+key);}

    private void validate(String key) {
        if (key == null || key.isBlank() || key.length() > 120)
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "该操作需要有效的 Idempotency-Key");
    }
}

