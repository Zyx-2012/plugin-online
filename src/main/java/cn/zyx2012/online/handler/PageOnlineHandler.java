package cn.zyx2012.online.handler;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.endpoint.WebSocketEndpoint;
import run.halo.app.extension.GroupVersion;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
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
        String origin = session.getHandshakeInfo().getHeaders().getOrigin();
        if (!isAllowedOrigin(origin)) {
            log.warn("[Security] 非法跨站连接请求已被拦截，来源: {}", origin);
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

                log.info("[Online-Plugin] 用户进入页面: {}, 当前该页人数: {}", uri, pageMap.get(uri).size());
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
                log.info("[Online-Plugin] 用户离开页面: {}, 剩余人数: {}", uri, remain);

                return broadcast(uri);
            }));
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

    private boolean isAllowedOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }

        try {
            String host = URI.create(origin).getHost();
            if (host == null) {
                return false;
            }
            return "localhost".equals(host)
                || "127.0.0.1".equals(host)
                || "zyx2012.cn".equals(host)
                || host.endsWith(".zyx2012.cn");
        } catch (Exception e) {
            return false;
        }
    }

    private record OnlineSample(Instant timestamp, int total) {
    }
}