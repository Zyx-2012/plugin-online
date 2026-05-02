package cn.zyx2012.online.controller;

import cn.zyx2012.online.handler.PageOnlineHandler;
import cn.zyx2012.online.handler.PageOnlineHandler.ReadingProgress;
import cn.zyx2012.online.security.IpRateLimiter;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import run.halo.app.plugin.ApiVersion;

import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApiVersion("online-user.zyx2012.cn/v1alpha1")
@RestController
@RequestMapping("/stats")
public class OnlineController {

    private static final long DEFAULT_RATE_LIMIT_MS = 3_000L;

    private final PageOnlineHandler handler;
    private final IpRateLimiter rateLimiter;

    public OnlineController(PageOnlineHandler handler, IpRateLimiter rateLimiter) {
        this.handler = handler;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public List<Map<String, Object>> getStats(Principal principal, ServerHttpRequest request) {
        PageOnlineHandler.BasicSetting setting = handler.getBasicSettingSnapshot();
        long rateLimitMs = setting.normalizedRateLimitInterval() * 1_000L;

        String clientIp = IpRateLimiter.extractClientIp(request);
        String referer = request.getHeaders().getFirst("Referer");
        boolean isFromConsole = referer != null && referer.contains("/console");
        if (!isFromConsole && !rateLimiter.isAllowed(clientIp + ":stats", rateLimitMs)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试。");
        }

        boolean anonymousRequest = principal == null;
        boolean exposeDetailToCurrentRequest = setting.exposeDetailPathsEnabled() || !anonymousRequest;

        return handler.getPageStats(setting.normalizedCleanSessionTime()).stream()
            .filter(pageStat -> !anonymousRequest || !pageStat.privatePage())
            .map(pageStat -> {
                boolean redactAllDetails = !exposeDetailToCurrentRequest;
                Map<String, Object> stat = new HashMap<>();
                stat.put("uri", redactAllDetails ? "-1" : pageStat.uri());
                stat.put("count", redactAllDetails ? -1 : pageStat.count());
                stat.put("lastActiveAt", redactAllDetails ? null : pageStat.lastActiveAt());
                stat.put("viewUrl", redactAllDetails ? "-1" : pageStat.uri());
                return stat;
            })
            .sorted((a, b) -> (int) b.get("count") - (int) a.get("count"))
            .collect(Collectors.toList());
    }

    @GetMapping("/summary")
    public Map<String, Object> getSummary(Principal principal, ServerHttpRequest request) {
        PageOnlineHandler.BasicSetting setting = handler.getBasicSettingSnapshot();
        long rateLimitMs = setting.normalizedRateLimitInterval() * 1_000L;

        String clientIp = IpRateLimiter.extractClientIp(request);
        String referer = request.getHeaders().getFirst("Referer");
        boolean isFromConsole = referer != null && referer.contains("/console");
        if (!isFromConsole && !rateLimiter.isAllowed(clientIp + ":summary", rateLimitMs)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试。");
        }
        int cleanSessionTime = setting.normalizedCleanSessionTime();
        Map<String, Object> summary = new HashMap<>();
        summary.put("total", handler.getCurrentTotalOnline(cleanSessionTime));
        summary.put("peak24h", handler.getPeakOnlineInLast24Hours(cleanSessionTime));
        summary.put("activePages", handler.getActivePageCount(cleanSessionTime));
        summary.put("updatedAt", Instant.now());
        summary.put("wsActive", true);
        summary.put("refreshRate", setting.normalizedRefreshRate());
        summary.put("exposeDetailPaths", setting.exposeDetailPathsEnabled());
        summary.put("detailMasked", !setting.exposeDetailPathsEnabled() && principal == null);
        return summary;
    }

    /**
     * 上报当前页面的阅读进度。
     */
    @PostMapping("/reading-progress")
    public Map<String, Object> postReadingProgress(
        @RequestBody ReadingProgressPayload payload,
        Principal principal,
        ServerHttpRequest request
    ) {
        PageOnlineHandler.BasicSetting setting = handler.getBasicSettingSnapshot();
        long rateLimitMs = setting.normalizedRateLimitInterval() * 1_000L;

        String clientIp = IpRateLimiter.extractClientIp(request);
        if (!rateLimiter.isAllowed(clientIp + ":post-progress", rateLimitMs)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试。");
        }

        String sessionId = buildPseudoSessionId(request, principal, payload.getClientId());
        handler.updateReadingProgressAndBroadcast(sessionId, payload.getUri(), payload.getScrollPercentage(), payload.getScrollY());

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    /**
     * 获取指定页面的所有在线用户阅读进度。
     */
    @GetMapping("/reading-progress")
    public List<ReadingProgress> getReadingProgress(
        @RequestParam("uri") String uri,
        ServerHttpRequest request
    ) {
        PageOnlineHandler.BasicSetting setting = handler.getBasicSettingSnapshot();
        long rateLimitMs = setting.normalizedRateLimitInterval() * 1_000L;

        String clientIp = IpRateLimiter.extractClientIp(request);
        if (!rateLimiter.isAllowed(clientIp + ":get-progress", rateLimitMs)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试。");
        }

        return handler.getReadingProgressForUri(uri);
    }

    /**
     * 使用 IP + 用户标识构造一个伪会话 ID，用于在无 WebSocket 场景下区分用户。
     */
    private String buildPseudoSessionId(ServerHttpRequest request, Principal principal, String clientId) {
        if (clientId != null && !clientId.isBlank()) {
            return "http:c:" + clientId;
        }
        String base = IpRateLimiter.extractClientIp(request);
        if (principal != null && principal.getName() != null) {
            base = principal.getName() + ":" + base;
        }
        return "http:" + base;
    }

    @Data
    public static class ReadingProgressPayload {
        private String uri;
        private double scrollPercentage;
        private int scrollY;
        private String clientId;
    }
}
