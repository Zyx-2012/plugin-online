package cn.zyx2012.online.controller;

import cn.zyx2012.online.handler.PageOnlineHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import run.halo.app.plugin.ApiVersion;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApiVersion("online-user.zyx2012.cn/v1alpha1")
@RestController
@RequestMapping("/stats")
public class OnlineController {

    private final PageOnlineHandler handler;

    public OnlineController(PageOnlineHandler handler) {
        this.handler = handler;
    }

    @GetMapping
    public List<Map<String, Object>> getStats() {
        handler.cleanupClosedSessions();

        return handler.getPageMap().entrySet().stream()
            .filter(entry -> !entry.getValue().isEmpty())
            .map(entry -> {
                Map<String, Object> stat = new HashMap<>();
                stat.put("uri", entry.getKey());
                stat.put("count", entry.getValue().size());
                stat.put("lastActiveAt", handler.getLastActiveMap().get(entry.getKey()));
                stat.put("viewUrl", entry.getKey());
                return stat;
            })
            .sorted((a, b) -> (int) b.get("count") - (int) a.get("count"))
            .collect(Collectors.toList());
    }

    @GetMapping("/summary")
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("total", handler.getCurrentTotalOnline());
        summary.put("peak24h", handler.getPeakOnlineInLast24Hours());
        summary.put("activePages", handler.getActivePageCount());
        summary.put("updatedAt", Instant.now());
        summary.put("wsActive", true);
        return summary;
    }

    @GetMapping("/total")
    public Map<String, Object> getTotal() {
        return Map.of("total", handler.getCurrentTotalOnline());
    }
}