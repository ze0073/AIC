package com.shijie.accessibility.online;

import com.shijie.accessibility.common.ApiException;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Service
public class TargetScopeService {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.shijie.accessibility.hosting.HostingTargetRegistry hosting;
    public java.util.Map<String,String> requestHeaders(OnlineTargetService.TargetView target){return hosting==null?java.util.Map.of():hosting.headers(target);}
    public String toolAddress(OnlineTargetService.TargetView target){return hosting==null?pinnedAddress(target):hosting.toolAddress(target);}
    public ValidatedTarget previewRegistration(String baseUrl) {
        URI uri = parse(baseUrl);
        return new ValidatedTarget(uri.toString(), origin(uri), resolve(uri.getHost()).stream().sorted().toList(),
                java.util.List.of(normalizePath(uri.getPath())));
    }

    public ValidatedTarget validateRegistration(String baseUrl, java.util.List<String> declaredAddresses,
                                                java.util.List<String> pathPrefixes) {
        URI uri = parse(baseUrl);
        String origin = origin(uri);
        Set<String> resolved = resolve(uri.getHost());
        Set<String> declared = new LinkedHashSet<>(declaredAddresses == null ? java.util.List.of() : declaredAddresses);
        if (declared.isEmpty()) throw ApiException.badRequest("ADDRESSES_REQUIRED", "必须登记当前 DNS 解析地址");
        if (!declared.equals(resolved)) {
            throw ApiException.badRequest("DNS_SCOPE_MISMATCH", "登记地址必须与目标当前的 DNS 解析结果一致，不能额外添加地址");
        }
        java.util.List<String> prefixes = pathPrefixes == null || pathPrefixes.isEmpty()
                ? java.util.List.of(normalizePath(uri.getPath()))
                : pathPrefixes.stream().map(TargetScopeService::normalizePath).distinct().limit(20).toList();
        if (prefixes.stream().noneMatch(prefix -> allowedPath(uri.getPath(), prefix))) {
            throw ApiException.badRequest("PATH_OUT_OF_SCOPE", "入口地址不在登记的路径范围内");
        }
        return new ValidatedTarget(uri.toString(), origin, declared.stream().sorted().toList(), prefixes);
    }

    public URI requireAllowed(OnlineTargetService.TargetView target, String candidate) {
        URI uri = parse(candidate);
        if (!origin(uri).equals(target.allowedOrigin())) {
            throw ApiException.badRequest("REDIRECT_OUT_OF_SCOPE", "请求或重定向离开登记的 Origin");
        }
        Set<String> resolved = resolve(uri.getHost());
        if (!new LinkedHashSet<>(target.allowedAddresses()).containsAll(resolved)) {
            throw ApiException.badRequest("DNS_REBINDING_BLOCKED", "目标 DNS 解析结果已超出登记范围");
        }
        if (target.pathPrefixes().stream().noneMatch(prefix -> allowedPath(uri.getPath(), prefix))) {
            throw ApiException.badRequest("PATH_OUT_OF_SCOPE", "请求路径超出登记范围");
        }
        return uri;
    }

    public String pinnedAddress(OnlineTargetService.TargetView target){
        requireAllowed(target,target.baseUrl());
        if(target.allowedAddresses().isEmpty())throw ApiException.badRequest("ADDRESSES_REQUIRED","目标没有经授权的地址");
        String address=target.allowedAddresses().getFirst();
        if(!address.matches("[0-9a-fA-F:.]+"))throw ApiException.badRequest("INVALID_PINNED_ADDRESS","登记地址不是 IP 字面量");
        return address;
    }

    private URI parse(String value) {
        try {
            URI original=URI.create(value);
            String rawPath=original.getRawPath();
            if(rawPath!=null&&(rawPath.contains("%")||rawPath.contains("\\")||rawPath.contains(";")))throw new IllegalArgumentException();
            URI uri = original.normalize();
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (Exception ex) {
            throw ApiException.badRequest("INVALID_TARGET_URL", "目标地址必须是合法的 HTTP 或 HTTPS URL，且不能包含用户信息或片段");
        }
    }

    Set<String> resolve(String host) {
        if(hosting!=null&&hosting.registeredHost(host))return Set.of("127.0.0.1");
        try {
            Set<String> result = new LinkedHashSet<>();
            Arrays.stream(InetAddress.getAllByName(host)).map(InetAddress::getHostAddress).forEach(result::add);
            if (result.isEmpty()) throw new IllegalArgumentException();
            return result;
        } catch (Exception ex) {
            throw ApiException.badRequest("DNS_RESOLUTION_FAILED", "无法解析目标主机");
        }
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port < 0 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }

    private static String normalizePath(String value) {
        String path = value == null || value.isBlank() ? "/" : value;
        if(path.contains("%")||path.contains("\\")||path.contains(";")||path.contains("..")||path.contains("/./"))
            throw ApiException.badRequest("INVALID_SCOPE_PATH","路径范围不能包含编码、转义或点段");
        if (!path.startsWith("/")) path = "/" + path;
        return path.endsWith("/") || "/".equals(path) ? path : path + "/";
    }

    private static boolean allowedPath(String rawPath, String rawPrefix) {
        String path = rawPath == null || rawPath.isBlank() ? "/" : rawPath;
        String prefix = normalizePath(rawPrefix);
        return "/".equals(prefix) || (path.endsWith("/") ? path : path + "/").startsWith(prefix);
    }

    public record ValidatedTarget(String baseUrl, String origin, java.util.List<String> addresses,
                                  java.util.List<String> pathPrefixes) {}
}
