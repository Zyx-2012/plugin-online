package cn.zyx2012.online.handler;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.endpoint.WebSocketEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
public class PageOnlineHandler implements WebSocketHandler, WebSocketEndpoint {

    @Getter
    private final Map<String, Set<WebSocketSession>> pageMap = new ConcurrentHashMap<>();

    @Getter
    private final Map<String, Instant> lastActiveMap = new ConcurrentHashMap<>();

    private final Deque<OnlineSample> totalSamples = new ArrayDeque<>();
    private final ReactiveSettingFetcher settingFetcher;

    public PageOnlineHandler(ReactiveSettingFetcher settingFetcher) {
        this.settingFetcher = settingFetcher;
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("online-user.zyx2012.cn", "v1alpha1");
    }

    @Override
    public String urlPath() {
        return "/online-ws";
    }

    @Override
    public WebSocketHandler handler() {
        return this;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        return getBasicSetting().flatMap(setting -> {
            String origin = session.getHandshakeInfo().getHeaders().getOrigin();
            if (!isAllowedOrigin(origin, setting)) {
                log.warn("[Security] 非法跨站连接请求已被拦截，来源: {}, 白名单: {}", origin, setting.originWhitelist());
                return session.close();
            }

            return session.receive()
                .map(message -> message.getPayloadAsText())
                .concatMap(payload -> {
                    if ("ping".equalsIgnoreCase(payload)) {
                        String uri = (String) session.getAttributes().get("uri");
                        if (uri != null) {
                            touch(uri);
                        }
                        return Mono.empty();
                    }

                    if (session.getAttributes().containsKey("uri")) {
                        log.warn("[Security] 用户尝试重复发送路径或篡改数据，已拦截。");
                        return Mono.empty();
                    }

                    String uri = payload;
                    pageMap.computeIfAbsent(uri, key -> new CopyOnWriteArraySet<>()).add(session);
                    session.getAttributes().put("uri", uri);
                    touch(uri);
                    recordTotalSample();

                    return broadcast(uri);
                })
                .then(Mono.defer(() -> {
                    String uri = (String) session.getAttributes().get("uri");
                    if (uri == null) {
                        return Mono.empty();
                    }

                    Set<WebSocketSession> sessions = pageMap.get(uri);
                    if (sessions != null) {
                        sessions.remove(session);
                        if (sessions.isEmpty()) {
                            pageMap.remove(uri);
                        }
                    }

                    touch(uri);
                    recordTotalSample();

                    int remain = pageMap.getOrDefault(uri, Set.of()).size();

                    return broadcast(uri);
                }));
        });
    }

    private Mono<BasicSetting> getBasicSetting() {
        return settingFetcher.fetch(BasicSetting.GROUP, BasicSetting.class)
            .onErrorResume(ex -> {
                log.debug("读取插件配置失败，使用默认配置。", ex);
                return Mono.just(BasicSetting.defaultValue());
            })
            .defaultIfEmpty(BasicSetting.defaultValue());
    }

    private Mono<Void> broadcast(String uri) {
        Set<WebSocketSession> sessions = pageMap.get(uri);
        if (sessions == null || sessions.isEmpty()) {
            return Mono.empty();
        }

        int onlineCount = (int) sessions.stream()
            .filter(WebSocketSession::isOpen)
            .count();

        String count = String.valueOf(onlineCount);

        return Flux.fromIterable(sessions)
            .filter(WebSocketSession::isOpen)
            .concatMap(session ->
                session.send(Mono.just(session.textMessage(count)))
                    .onErrorResume(ex -> {
                        log.debug("广播在线人数失败, uri={}, sessionId={}", uri, session.getId(), ex);
                        return Mono.empty();
                    })
            )
            .then();
    }

    private void touch(String uri) {
        lastActiveMap.put(uri, Instant.now());
    }

    public int getCurrentTotalOnline() {
        cleanupClosedSessions();
        return pageMap.values().stream()
            .mapToInt(sessions -> (int) sessions.stream().filter(WebSocketSession::isOpen).count())
            .sum();
    }

    public int getActivePageCount() {
        cleanupClosedSessions();
        return (int) pageMap.entrySet().stream()
            .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
            .count();
    }

    public int getPeakOnlineInLast24Hours() {
        cleanupExpiredSamples();
        return totalSamples.stream()
            .mapToInt(OnlineSample::total)
            .max()
            .orElse(getCurrentTotalOnline());
    }

    private synchronized void recordTotalSample() {
        cleanupExpiredSamples();
        totalSamples.addLast(new OnlineSample(Instant.now(), getCurrentTotalOnline()));
    }

    private synchronized void cleanupExpiredSamples() {
        Instant threshold = Instant.now().minus(Duration.ofHours(24));
        while (!totalSamples.isEmpty() && totalSamples.peekFirst().timestamp().isBefore(threshold)) {
            totalSamples.pollFirst();
        }
    }

    public void cleanupClosedSessions() {
        pageMap.forEach((uri, sessions) -> {
            sessions.removeIf(session -> !session.isOpen());
            if (sessions.isEmpty()) {
                pageMap.remove(uri);
            }
        });
    }

    private boolean isAllowedOrigin(String origin, BasicSetting setting) {
        if (origin == null || origin.isBlank()) {
            return true;
        }

        try {
            URI originUri = URI.create(origin);
            String host = originUri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }

            String normalizedHost = host.toLowerCase(Locale.ROOT);
            int port = originUri.getPort();

            Set<String> rules = new LinkedHashSet<>();
            rules.add("localhost");
            rules.add("127.0.0.1");
            rules.add("zyx2012.cn");
            rules.add("*.zyx2012.cn");
            rules.addAll(parseWhitelist(setting.originWhitelist()));

            for (String rule : rules) {
                if (matchesRule(rule, normalizedHost, port, origin)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("解析 Origin 失败: {}", origin, e);
            return false;
        }
    }

    private Set<String> parseWhitelist(String raw) {
        Set<String> rules = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return rules;
        }

        String[] segments = raw.split("[,\\n\\r]+");
        for (String segment : segments) {
            String item = segment == null ? "" : segment.trim();
            if (!item.isEmpty()) {
                rules.add(item.toLowerCase(Locale.ROOT));
            }
        }
        return rules;
    }

    private boolean matchesRule(String rule, String host, int port, String rawOrigin) {
        String normalizedRule = rule.trim().toLowerCase(Locale.ROOT);
        if (normalizedRule.isEmpty()) {
            return false;
        }

        if ("*".equals(normalizedRule)) {
            return true;
        }

        if (normalizedRule.startsWith("http://")
            || normalizedRule.startsWith("https://")
            || normalizedRule.startsWith("ws://")
            || normalizedRule.startsWith("wss://")) {
            try {
                URI ruleUri = URI.create(normalizedRule);
                String ruleHost = ruleUri.getHost();
                int rulePort = ruleUri.getPort();
                if (ruleHost == null) {
                    return false;
                }
                boolean hostMatched = host.equals(ruleHost.toLowerCase(Locale.ROOT));
                boolean portMatched = rulePort == -1 || rulePort == port;
                return hostMatched && portMatched;
            } catch (Exception ignored) {
                return false;
            }
        }

        if (normalizedRule.startsWith("*.")) {
            String suffix = normalizedRule.substring(1);
            return host.endsWith(suffix);
        }

        if (normalizedRule.contains(":")) {
            String hostPort = port == -1 ? host : host + ":" + port;
            return hostPort.equals(normalizedRule);
        }

        return host.equals(normalizedRule);
    }

    private record OnlineSample(Instant timestamp, int total) {
    }

    public record BasicSetting(
        @JsonProperty("clean_session_time")
        Integer cleanSessionTime,
        @JsonProperty("expose_detail_paths")
        Boolean exposeDetailPaths,
        @JsonProperty("refresh_rate")
        Integer refreshRate,
        @JsonProperty("origin_whitelist")
        String originWhitelist
    ) {
        public static final String GROUP = "basic";

        public static BasicSetting defaultValue() {
            return new BasicSetting(600, false, 10, "");
        }
    }
}