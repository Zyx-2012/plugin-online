package cn.zyx2012.online.security;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于内存的简单 IP 限流器（固定窗口）。
 * 适用于插件级别轻量级防护，不替代网关层限流。
 */
@Component
public class IpRateLimiter {

    private final Map<String, Long> lastRequestTime = new ConcurrentHashMap<>();

    /**
     * 判断该 key 是否允许在指定最小间隔内再次访问。
     *
     * @param key          限流键（如 IP + ":" + apiPath）
     * @param minIntervalMs 最小请求间隔，单位毫秒
     * @return true 表示允许访问
     */
    public boolean isAllowed(String key, long minIntervalMs) {
        long now = System.currentTimeMillis();
        AtomicBoolean allowed = new AtomicBoolean(false);
        lastRequestTime.compute(key, (k, last) -> {
            if (last == null || now - last >= minIntervalMs) {
                allowed.set(true);
                return now;
            }
            return last;
        });
        return allowed.get();
    }

    /**
     * 提取客户端真实 IP。
     * 从 X-Forwarded-For 右侧（靠近代理端）向左侧（靠近客户端）遍历，
     * 跳过已知内网地址，取第一个非内网 IP。
     * 这是防止客户端伪造 X-Forwarded-For 绕过限流的关键措施。
     */
    public static String extractClientIp(ServerHttpRequest request) {
        String xfwd = request.getHeaders().getFirst("X-Forwarded-For");
        if (xfwd != null && !xfwd.isBlank()) {
            String[] ips = xfwd.split(",");
            for (int i = ips.length - 1; i >= 0; i--) {
                String ip = ips[i].trim();
                if (!ip.isEmpty() && !isPrivateIp(ip)) {
                    return ip;
                }
            }
        }
        String xri = request.getHeaders().getFirst("X-Real-Ip");
        if (xri != null && !xri.isBlank()) {
            String ip = xri.trim();
            if (!ip.isEmpty() && !isPrivateIp(ip)) {
                return ip;
            }
        }
        return request.getRemoteAddress() != null
            ? request.getRemoteAddress().getAddress().getHostAddress()
            : "unknown";
    }

    private static boolean isPrivateIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return true;
        }
        if ("127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            return true;
        }
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("172.")) {
            if (ip.startsWith("172.")) {
                try {
                    int second = Integer.parseInt(ip.split("\\.")[1]);
                    if (second >= 16 && second <= 31) {
                        return true;
                    }
                } catch (Exception ignored) {
                    return true;
                }
            } else {
                return true;
            }
        }
        return false;
    }
}
