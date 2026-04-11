package cn.zyx2012.online.controller;

import cn.zyx2012.online.handler.PageOnlineHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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

    private final PageOnlineHandler handler;

    public OnlineController(PageOnlineHandler handler) {
        this.handler = handler;
    }

    @GetMapping
    public List<Map<String, Object>> getStats(Principal principal) {
        PageOnlineHandler.BasicSetting setting = handler.getBasicSettingSnapshot();
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
    public Map<String, Object> getSummary(Principal principal) {
        PageOnlineHandler.BasicSetting setting = handler.getBasicSettingSnapshot();
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
}
