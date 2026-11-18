package com.shijie.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CampusControllerTest {
    @TempDir Path temporary;

    @Test void bearerIdentityCannotBeSpoofedWithRequestHeaders() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenReturn(2);
        var fixed=new CampusController(jdbc,"fixed",temporary);
        HttpHeaders spoofed=new HttpHeaders();spoofed.add("X-Demo-User","admin");spoofed.add("X-Demo-Role","ADMIN");
        assertEquals(HttpStatus.UNAUTHORIZED,fixed.admin(spoofed).getStatusCode());
        HttpHeaders ordinary=bearer("demo-user-a");
        assertEquals(HttpStatus.FORBIDDEN,fixed.admin(ordinary).getStatusCode());
        var defective=new CampusController(jdbc,"defective",temporary);
        assertEquals(HttpStatus.OK,defective.admin(ordinary).getStatusCode());
    }

    @Test void crossUserAndXssDifferBetweenModes() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(2L))).thenReturn(List.of(Map.of("id",2L,"owner","user-b","student_name","李同学","course","Web 安全")));
        var fixed=new CampusController(jdbc,"fixed",temporary);
        var defective=new CampusController(jdbc,"defective",temporary);
        assertEquals(HttpStatus.FORBIDDEN,fixed.application(2,bearer("demo-user-a")).getStatusCode());
        assertEquals(HttpStatus.OK,defective.application(2,bearer("demo-user-a")).getStatusCode());
        assertTrue(defective.greeting("<script>alert(1)</script>").contains("<script>"));
        assertFalse(fixed.greeting("<script>alert(1)</script>").contains("<script>"));
    }

    @Test void harmlessMismatchedUploadIsAcceptedOnlyInDefectiveMode() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        var file=new MockMultipartFile("file","quality-check.jpg","image/jpeg","harmless marker".getBytes());
        var fixed=new CampusController(jdbc,"fixed",temporary);
        assertEquals(HttpStatus.BAD_REQUEST,fixed.upload(file,bearer("demo-user-a")).getStatusCode());
        var defective=new CampusController(jdbc,"defective",temporary);
        assertEquals(HttpStatus.OK,defective.upload(file,bearer("demo-user-a")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,defective.upload(file,new HttpHeaders()).getStatusCode());
    }

    @Test void redirectMatrixHasOnlyFixedDestinationsAndProbeCounts() throws Exception {
        var controller=new CampusController(mock(JdbcTemplate.class),"fixed",temporary);
        assertEquals(302,controller.scopeRedirectMatrix("host",302).getStatusCode().value());
        assertEquals("http://scope-alias:8091/api/redirect-probe/hit",
                controller.scopeRedirectMatrix("host",302).getHeaders().getLocation().toString());
        assertEquals("/redirect-probe-outside",controller.scopeRedirectMatrix("path",307).getHeaders().getLocation().toString());
        assertEquals("https://campus-demo:8091/api/redirect-probe/hit",
                controller.scopeRedirectMatrix("scheme",308).getHeaders().getLocation().toString());
        assertEquals(400,controller.scopeRedirectMatrix("inside",300).getStatusCode().value());
        assertEquals(404,controller.scopeRedirectMatrix("unknown",302).getStatusCode().value());
        assertEquals(0,controller.redirectProbeCounters().get("outside"));
        controller.insideRedirectProbe();
        assertEquals(1,controller.redirectProbeCounters().get("inside"));
    }

    private HttpHeaders bearer(String token){HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(token);return headers;}
}
