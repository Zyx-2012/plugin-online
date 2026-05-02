package cn.zyx2012.online.handler;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
public class PageOnlineHandler implements WebSocketHandler, WebSocketEndpoint {

    private static final int MAX_URI_LENGTH = 2048;
    private static final Set<String> SENSITIVE_PATH_PREFIXES = Set.of(
        "/apis",
        "/console",
        "/login",
        "/logout",
        "/oauth2",
        "/uc"
    );

    @Getter
    private final Map<String, Set<WebSocketSession>> pageMap = new ConcurrentHashMap<>();

    @Getter
    private final Map<String, Instant> lastActiveMap = new ConcurrentHashMap<>();

    private final Map<String, Boolean> privatePageMap = new ConcurrentHashMap<>();
    private final Map<String, Instant> sessionActiveMap = new ConcurrentHashMap<>();
    private final Map<String, Boolean> sessionPrivateMap = new ConcurrentHashMap<>();
    private final Map<String, String> sessionAnonymousIdMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ReadingProgress>> readingProgressMap = new ConcurrentHashMap<>();
    private final Map<String, Instant> httpSessionActiveMap = new ConcurrentHashMap<>();
    private final Map<String, String> sessionUriMap = new ConcurrentHashMap<>();
    private final Deque<OnlineSample> totalSamples = new ArrayDeque<>();
    private final ReactiveSettingFetcher settingFetcher;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
            cleanupClosedSessions(setting.normalizedCleanSessionTime());

            String origin = session.getHandshakeInfo().getHeaders().getOrigin();
            if (!isAllowedOrigin(origin, setting)) {
                log.warn("[Security] 非法跨站连接请求已被拦截，来源: {}, 白名单: {}", origin, setting.originWhitelist());
                return session.close();
            }

            return session.receive()
                .map(message -> message.getPayloadAsText())
                .concatMap(payload -> {
                    if ("ping".equalsIgnoreCase(payload)) {
                        touchSession(session);
                        String uri = sessionUriMap.get(session.getId());
                        if (uri != null) {
                            touch(uri);
                        }
                        return Mono.empty();
                    }

                    ClientPayload clientPayload = parsePayload(payload);
                    String uri = normalizeUri(clientPayload.uri());
                    if (uri == null) {
                        log.warn("[Security] 用户发送了非法路径载荷，已忽略。sessionId={}", session.getId());
                        return Mono.empty();
                    }

                    String existingUri = sessionUriMap.putIfAbsent(session.getId(), uri);
                    if (existingUri != null) {
                        log.warn("[Security] 用户尝试重复发送路径或篡改数据，已拦截。sessionId={}", session.getId());
                        return Mono.empty();
                    }

                    pageMap.computeIfAbsent(uri, key -> new CopyOnWriteArraySet<>()).add(session);
                    boolean protectedPage = clientPayload.privatePage() || isSensitiveUri(uri);
                    sessionPrivateMap.put(session.getId(), protectedPage);
                    privatePageMap.put(uri, hasProtectedSession(pageMap.get(uri)));
                    session.getAttributes().put("uri", uri);
                    touchSession(session);
                    touch(uri);
                    recordTotalSample(setting.normalizedCleanSessionTime());

                    return broadcast(uri);
                })
                .then(Mono.defer(() -> {
                    String uri = sessionUriMap.remove(session.getId());
                    if (uri == null) {
                        return Mono.empty();
                    }

                    Set<WebSocketSession> sessions = pageMap.get(uri);
                    boolean pageStillActive = false;
                    if (sessions != null) {
                        sessions.remove(session);
                        if (sessions.isEmpty()) {
                            pageMap.remove(uri);
                            privatePageMap.remove(uri);
                            lastActiveMap.remove(uri);
                        } else {
                            privatePageMap.put(uri, hasProtectedSession(sessions));
                            pageStillActive = true;
                        }
                    }

                    sessionActiveMap.remove(session.getId());
                    sessionPrivateMap.remove(session.getId());
                    if (pageStillActive) {
                        touch(uri);
                    }
                    recordTotalSample(setting.normalizedCleanSessionTime());

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

        String msgJson;
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "count");
            msg.put("count", onlineCount);
            msgJson = objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            msgJson = "{\"type\":\"count\",\"count\":" + onlineCount + "}";
        }

        final String finalJson = msgJson;
        return Flux.fromIterable(sessions)
            .filter(WebSocketSession::isOpen)
            .concatMap(session ->
                session.send(Mono.just(session.textMessage(finalJson)))
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

    private void touchSession(WebSocketSession session) {
        sessionActiveMap.put(session.getId(), Instant.now());
    }

    public BasicSetting getBasicSettingSnapshot() {
        return settingFetcher.fetch(BasicSetting.GROUP, BasicSetting.class)
            .onErrorResume(ex -> {
                log.debug("读取插件配置失败，使用默认配置。", ex);
                return Mono.just(BasicSetting.defaultValue());
            })
            .defaultIfEmpty(BasicSetting.defaultValue())
            .blockOptional()
            .orElse(BasicSetting.defaultValue());
    }

    public List<PageStat> getPageStats(int cleanSessionTime) {
        cleanupClosedSessions(cleanSessionTime);
        return pageMap.entrySet().stream()
            .map(entry -> {
                int count = (int) entry.getValue().stream()
                    .filter(WebSocketSession::isOpen)
                    .count();
                return new PageStat(
                    entry.getKey(),
                    count,
                    lastActiveMap.get(entry.getKey()),
                    Boolean.TRUE.equals(privatePageMap.get(entry.getKey()))
                );
            })
            .filter(item -> item.count() > 0)
            .toList();
    }

    public int getCurrentTotalOnline(int cleanSessionTime) {
        cleanupClosedSessions(cleanSessionTime);
        return pageMap.values().stream()
            .mapToInt(sessions -> (int) sessions.stream().filter(WebSocketSession::isOpen).count())
            .sum();
    }

    public int getActivePageCount(int cleanSessionTime) {
        cleanupClosedSessions(cleanSessionTime);
        return (int) pageMap.entrySet().stream()
            .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
            .count();
    }

    public int getPeakOnlineInLast24Hours(int cleanSessionTime) {
        cleanupClosedSessions(cleanSessionTime);
        cleanupExpiredSamples();
        return totalSamples.stream()
            .mapToInt(OnlineSample::total)
            .max()
            .orElse(getCurrentTotalOnline(cleanSessionTime));
    }

    private synchronized void recordTotalSample(int cleanSessionTime) {
        cleanupExpiredSamples();
        totalSamples.addLast(new OnlineSample(Instant.now(), getCurrentTotalOnline(cleanSessionTime)));
    }

    private synchronized void cleanupExpiredSamples() {
        Instant threshold = Instant.now().minus(Duration.ofHours(24));
        while (!totalSamples.isEmpty() && totalSamples.peekFirst().timestamp().isBefore(threshold)) {
            totalSamples.pollFirst();
        }
    }

    public void cleanupClosedSessions() {
        cleanupClosedSessions(getBasicSettingSnapshot().normalizedCleanSessionTime());
    }

    public void cleanupClosedSessions(int cleanSessionTime) {
        Instant threshold = Instant.now().minusSeconds(Math.max(cleanSessionTime, 30));
        pageMap.forEach((uri, sessions) -> {
            sessions.removeIf(session -> isExpiredSession(session, threshold));
            if (sessions.isEmpty()) {
                pageMap.remove(uri);
                privatePageMap.remove(uri);
                lastActiveMap.remove(uri);
            }
        });
        sessionActiveMap.entrySet().removeIf(entry ->
            entry.getValue() == null || entry.getValue().isBefore(threshold)
        );
        sessionPrivateMap.keySet().removeIf(sessionId -> !sessionActiveMap.containsKey(sessionId));
        sessionAnonymousIdMap.keySet().removeIf(sessionId -> !sessionActiveMap.containsKey(sessionId));
        sessionUriMap.keySet().removeIf(sessionId -> !sessionActiveMap.containsKey(sessionId));
        readingProgressMap.forEach((uri, progressMap) -> {
            progressMap.keySet().removeIf(sessionId -> {
                boolean isWsActive = sessionActiveMap.containsKey(sessionId);
                boolean isHttpActive = httpSessionActiveMap.containsKey(sessionId)
                    && httpSessionActiveMap.get(sessionId).isAfter(threshold);
                return !isWsActive && !isHttpActive;
            });
        });
        readingProgressMap.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        httpSessionActiveMap.entrySet().removeIf(entry ->
            entry.getValue() == null || entry.getValue().isBefore(threshold)
        );
    }

    private boolean isExpiredSession(WebSocketSession session, Instant threshold) {
        if (!session.isOpen()) {
            sessionActiveMap.remove(session.getId());
            sessionPrivateMap.remove(session.getId());
            sessionUriMap.remove(session.getId());
            return true;
        }
        Instant lastSeen = sessionActiveMap.get(session.getId());
        if (lastSeen == null || lastSeen.isBefore(threshold)) {
            sessionActiveMap.remove(session.getId());
            sessionPrivateMap.remove(session.getId());
            sessionUriMap.remove(session.getId());
            return true;
        }
        return false;
    }

    private boolean hasProtectedSession(Set<WebSocketSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return false;
        }
        return sessions.stream().anyMatch(session ->
            Boolean.TRUE.equals(sessionPrivateMap.get(session.getId()))
        );
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
            rules.addAll(parseWhitelist(setting.normalizedOriginWhitelist()));

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

    private ClientPayload parsePayload(String payload) {
        String raw = payload == null ? "" : payload.trim();
        if (raw.startsWith("{")) {
            try {
                JsonNode root = objectMapper.readTree(raw);
                return new ClientPayload(
                    root.path("uri").asText(""),
                    root.path("privatePage").asBoolean(false)
                );
            } catch (Exception ex) {
                log.debug("解析客户端载荷失败，按旧版纯路径格式处理。payload={}", raw, ex);
            }
        }
        return new ClientPayload(raw, false);
    }

    private String normalizeUri(String rawUri) {
        if (rawUri == null) {
            return null;
        }

        String normalized = rawUri.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_URI_LENGTH) {
            return null;
        }

        normalized = normalized.replace('\\', '/');
        int fragmentIndex = normalized.indexOf('#');
        if (fragmentIndex >= 0) {
            normalized = normalized.substring(0, fragmentIndex);
        }

        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }

        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            try {
                normalized = URI.create(normalized).getPath();
            } catch (Exception ex) {
                return null;
            }
        }

        if (normalized == null || normalized.isBlank()) {
            return "/";
        }

        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }

        if (normalized.contains("\u0000") || normalized.contains("..")) {
            return null;
        }

        return normalized.length() > MAX_URI_LENGTH ? null : normalized;
    }

    private boolean isSensitiveUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return false;
        }
        return SENSITIVE_PATH_PREFIXES.stream().anyMatch(prefix ->
            uri.equals(prefix) || uri.startsWith(prefix + "/")
        );
    }

    private record ClientPayload(String uri, boolean privatePage) {
    }

    private record OnlineSample(Instant timestamp, int total) {
    }

    public record PageStat(String uri, int count, Instant lastActiveAt, boolean privatePage) {
    }

    public record ReadingProgress(
        String anonymousId,
        double scrollPercentage,
        int scrollY,
        Instant updatedAt
    ) {
    }

    public void updateReadingProgress(String sessionId, String uri, double scrollPercentage, int scrollY) {
        String normalizedUri = normalizeUri(uri);
        if (normalizedUri == null) {
            return;
        }
        httpSessionActiveMap.put(sessionId, Instant.now());
        String anonymousId = sessionAnonymousIdMap.computeIfAbsent(sessionId, k -> UUID.randomUUID().toString().substring(0, 8));
        Map<String, ReadingProgress> pageProgress = readingProgressMap.computeIfAbsent(normalizedUri, k -> new ConcurrentHashMap<>());
        pageProgress.put(sessionId, new ReadingProgress(anonymousId, scrollPercentage, scrollY, Instant.now()));
    }

    public List<ReadingProgress> getReadingProgressForUri(String uri) {
        String normalizedUri = normalizeUri(uri);
        if (normalizedUri == null) {
            return List.of();
        }
        Map<String, ReadingProgress> pageProgress = readingProgressMap.get(normalizedUri);
        if (pageProgress == null || pageProgress.isEmpty()) {
            return List.of();
        }
        Instant threshold = Instant.now().minusSeconds(120);
        return pageProgress.values().stream()
            .filter(p -> p.updatedAt() != null && p.updatedAt().isAfter(threshold))
            .toList();
    }

    /**
     * 向指定 URI 的所有 WebSocket 客户端广播阅读进度。
     * 排除上报者本人，仅推送其他用户的进度数据。
     */
    public void broadcastReadingProgress(String uri, String excludeSessionId) {
        String normalizedUri = normalizeUri(uri);
        if (normalizedUri == null) {
            return;
        }
        Set<WebSocketSession> sessions = pageMap.get(normalizedUri);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        String excludeAnonymousId = sessionAnonymousIdMap.get(excludeSessionId);
        List<ReadingProgress> progressList = getReadingProgressForUri(normalizedUri)
            .stream()
            .filter(p -> excludeAnonymousId == null || !excludeAnonymousId.equals(p.anonymousId()))
            .toList();

        String progressJson;
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "progress");
            msg.put("list", progressList);
            progressJson = objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            log.debug("序列化阅读进度失败", e);
            return;
        }

        final String finalJson = progressJson;
        Flux.fromIterable(sessions)
            .filter(s -> s.isOpen() && !s.getId().equals(excludeSessionId))
            .concatMap(s ->
                s.send(Mono.just(s.textMessage(finalJson)))
                    .onErrorResume(ex -> {
                        log.debug("推送阅读进度失败, sessionId={}", s.getId(), ex);
                        return Mono.empty();
                    })
            )
            .subscribe();
    }

    /**
     * 更新阅读进度并触发 WebSocket 广播。
     * 由 Controller 调用，替代原来仅写内存的逻辑。
     */
    public void updateReadingProgressAndBroadcast(
            String sessionId, String uri,
            double scrollPercentage, int scrollY) {
        updateReadingProgress(sessionId, uri, scrollPercentage, scrollY);
        broadcastReadingProgress(uri, sessionId);
    }

    public record BasicSetting(
        @JsonProperty("clean_session_time")
        Integer cleanSessionTime,
        @JsonProperty("expose_detail_paths")
        Boolean exposeDetailPaths,
        @JsonProperty("refresh_rate")
        Integer refreshRate,
        @JsonProperty("origin_whitelist")
        String originWhitelist,
        @JsonProperty("enable_reading_progress")
        Boolean enableReadingProgress,
        @JsonProperty("reading_progress_interval")
        Integer readingProgressInterval,
        @JsonProperty("rate_limit_interval")
        Integer rateLimitInterval
    ) {
        public static final String GROUP = "basic";

        public static BasicSetting defaultValue() {
            return new BasicSetting(600, false, 10, "", true, 5, 3);
        }

        public int normalizedCleanSessionTime() {
            if (cleanSessionTime == null) {
                return 600;
            }
            return Math.max(cleanSessionTime, 30);
        }

        public boolean exposeDetailPathsEnabled() {
            return Boolean.TRUE.equals(exposeDetailPaths);
        }

        public int normalizedRefreshRate() {
            if (refreshRate == null) {
                return 10;
            }
            return Math.max(3, Math.min(refreshRate, 120));
        }

        public String normalizedOriginWhitelist() {
            return originWhitelist == null ? "" : originWhitelist;
        }

        public boolean isReadingProgressEnabled() {
            return Boolean.TRUE.equals(enableReadingProgress);
        }

        public int normalizedReadingProgressInterval() {
            if (readingProgressInterval == null) {
                return 5;
            }
            return Math.max(2, Math.min(readingProgressInterval, 60));
        }

        public int normalizedRateLimitInterval() {
            if (rateLimitInterval == null) {
                return 3;
            }
            return Math.max(1, Math.min(rateLimitInterval, 60));
        }
    }
}
