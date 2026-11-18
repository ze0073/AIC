package com.shijie.accessibility.hosting;

import com.shijie.accessibility.online.CredentialCipher;
import com.shijie.accessibility.online.OnlineTargetService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.net.URI;
import java.util.*;

@Component
public class HostingTargetRegistry {
    private final JdbcTemplate jdbc;private final CredentialCipher cipher;private final int port;
    public HostingTargetRegistry(JdbcTemplate jdbc,CredentialCipher cipher,@Value("${app.hosting.gateway-port:8090}") int port){this.jdbc=jdbc;this.cipher=cipher;this.port=port;}
    public String url(String site,String deployment){return "http://d-"+deployment.toLowerCase(Locale.ROOT)+".s-"+site.toLowerCase(Locale.ROOT)+".localhost:"+port+"/";}
    public String liveUrl(String site){return "http://s-"+site.toLowerCase(Locale.ROOT)+".localhost:"+port+"/";}
    public boolean registeredHost(String host){
        if(host==null||!host.matches("d-[0-9a-z]{26}\\.s-[0-9a-z]{26}\\.localhost"))return false;
        String id=host.substring(2,28).toUpperCase(Locale.ROOT),site=host.substring(31,57).toUpperCase(Locale.ROOT);
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM hosted_deployments WHERE id=? AND site_id=? AND target_id IS NOT NULL",Integer.class,id,site);return count!=null&&count==1;
    }
    public Map<String,String> headers(OnlineTargetService.TargetView target){
        var rows=jdbc.queryForList("SELECT id,site_id,encrypted_access_token FROM hosted_deployments WHERE target_id=?",target.id());
        if(rows.isEmpty())return Map.of();var row=rows.getFirst();
        if(!url((String)row.get("site_id"),(String)row.get("id")).equals(target.baseUrl()))throw new IllegalStateException("托管目标地址已变更");
        return Map.of("X-Shijie-Access",cipher.decrypt((String)row.get("encrypted_access_token"),"deployment-access:"+row.get("id")));
    }
    public String toolAddress(OnlineTargetService.TargetView target){return registeredHost(URI.create(target.baseUrl()).getHost())?"host-gateway":target.allowedAddresses().getFirst();}
    public Optional<Map<String,Object>> snapshot(String targetId){
        var rows=jdbc.queryForList("SELECT d.id AS deploymentId,d.site_id AS siteId,d.source_version_id AS sourceVersionId,d.source_manifest AS sourceManifest,d.base_deployment_id AS baseDeploymentId,d.image_digest AS imageDigest,d.database_resource_id AS databaseResourceId,d.environment_kind AS environmentKind FROM hosted_deployments d WHERE target_id=?",targetId);return rows.stream().findFirst();
    }
    public OnlineTargetService.SecretCredential rebind(OnlineTargetService.SecretCredential secret,OnlineTargetService.TargetView target){
        var before=snapshot(secret.targetId());var after=snapshot(target.id());
        if(before.isPresent()&&after.isPresent()&&Objects.equals(before.get().get("siteId"),after.get().get("siteId")))return new OnlineTargetService.SecretCredential(secret.id(),target.id(),secret.principalType(),secret.authType(),secret.secret(),target.allowedOrigin());
        return secret;
    }
}
