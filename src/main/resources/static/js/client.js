(function () {
    const wsProtocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = `${wsProtocol}//${window.location.host}/apis/online-user.zyx2012.cn/v1alpha1/online-ws`;
    const apiBase = `${window.location.protocol}//${window.location.host}/apis/online-user.zyx2012.cn/v1alpha1`;

    let socket = null;
    let heartbeatTimer = null;
    let pathnameWatcher = null;
    let currentPath = null;
    let reconnectTimer = null;

    // 唯一客户端标识，用于区分同一 IP 下的不同用户
    const CLIENT_ID_KEY = "__online_monitor_client_id__";
    let clientId = localStorage.getItem(CLIENT_ID_KEY);
    if (!clientId) {
        clientId = Math.random().toString(36).substring(2, 10) + Date.now().toString(36);
        try {
            localStorage.setItem(CLIENT_ID_KEY, clientId);
        } catch (e) {
            // localStorage 不可用则使用内存中的临时 ID
        }
    }

    // ========== 阅读进度相关状态 ==========
    let progressReportTimer = null;
    let progressIndicators = [];
    const meta = window.__ONLINE_MONITOR_META__ || {};
    const RATE_LIMIT_INTERVAL_MS = (meta.rateLimitInterval || 3) * 1000;
    const PROGRESS_THROTTLE_MS = RATE_LIMIT_INTERVAL_MS;

    function getCurrentPath() {
        return window.location.pathname || "/";
    }

    function isPrivatePage() {
        return Boolean(window.__ONLINE_MONITOR_META__ && window.__ONLINE_MONITOR_META__.privatePage);
    }

    function buildRegisterPayload(path) {
        return JSON.stringify({
            uri: path,
            privatePage: isPrivatePage()
        });
    }

    function clearHeartbeat() {
        if (heartbeatTimer) {
            clearInterval(heartbeatTimer);
            heartbeatTimer = null;
        }
    }

    function clearReconnect() {
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
        }
    }

    function isSocketUsable(ws) {
        return Boolean(ws) && (
            ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING
        );
    }

    function scheduleReconnect() {
        clearReconnect();
        reconnectTimer = setTimeout(() => {
            connect(true);
        }, 1500);
    }

    function startHeartbeat(ws) {
        clearHeartbeat();
        heartbeatTimer = setInterval(() => {
            if (socket === ws && ws.readyState === WebSocket.OPEN) {
                ws.send("ping");
            }
        }, 30000);
    }

    function closeSocket() {
        clearHeartbeat();
        if (socket) {
            try {
                socket.onclose = null;
                socket.onerror = null;
                socket.close();
            } catch (e) {
                console.debug("[online-monitor] close socket failed", e);
            }
            socket = null;
        }
    }

    function emitRegistered(path) {
        window.dispatchEvent(new CustomEvent("online-monitor:registered", {
            detail: { path }
        }));
    }

    function emitPathChanged(path) {
        window.dispatchEvent(new CustomEvent("online-monitor:path-changed", {
            detail: { path }
        }));
    }

    function connect(force) {
        const nextPath = getCurrentPath();
        const samePath = currentPath === nextPath;

        // 当前连接仍然可用时，不因为 pageshow / visibilitychange 之类的事件重复重连
        if (samePath && isSocketUsable(socket)) {
            return;
        }

        closeSocket();
        clearReconnect();

        currentPath = nextPath;
        const ws = new WebSocket(wsUrl);
        socket = ws;

        ws.onopen = function () {
            // 确保只有当前最新的 socket 实例能继续执行
            if (socket !== ws || ws.readyState !== WebSocket.OPEN) return;

            ws.send(buildRegisterPayload(currentPath));
            startHeartbeat(ws);

            // 触发注册成功事件
            emitRegistered(currentPath);
            // 安装 WebSocket 消息处理器（接收进度推送）
            installWsMessageHandler(ws);
        };

        ws.onclose = function () {
            if (socket === ws) {
                socket = null;
                clearHeartbeat();
                scheduleReconnect();
            }
        };

        ws.onerror = function () {
            // onerror 后会触发 onclose，由 onclose 统一处理
        };
    }

    function refreshIfPathChanged() {
        const nextPath = getCurrentPath();
        if (nextPath !== currentPath) {
            emitPathChanged(nextPath);
            connect(true);
        }
    }

    function installHistoryHooks() {
        const rawPushState = history.pushState;
        const rawReplaceState = history.replaceState;

        history.pushState = function () {
            const result = rawPushState.apply(this, arguments);
            // 使用 microtask 确保在路径更新后检查
            Promise.resolve().then(refreshIfPathChanged);
            return result;
        };

        history.replaceState = function () {
            const result = rawReplaceState.apply(this, arguments);
            Promise.resolve().then(refreshIfPathChanged);
            return result;
        };
    }

    function installRouteListeners() {
        window.addEventListener("popstate", refreshIfPathChanged);
        window.addEventListener("hashchange", refreshIfPathChanged);

        window.addEventListener("pageshow", function () {
            if (!isSocketUsable(socket)) {
                connect(true);
            }
        });

        document.addEventListener("visibilitychange", function () {
            if (!document.hidden) {
                refreshIfPathChanged();
                if (!isSocketUsable(socket)) {
                    connect(true);
                }
            }
        });
    }

    function startPathWatcher() {
        if (pathnameWatcher) clearInterval(pathnameWatcher);
        // 降低轮询频率或作为最后的保底
        pathnameWatcher = setInterval(refreshIfPathChanged, 2000);
    }

    // ==================== 阅读进度模块 ====================

    function throttle(fn, limit) {
        let inThrottle;
        return function (...args) {
            if (!inThrottle) {
                fn.apply(this, args);
                inThrottle = true;
                setTimeout(() => (inThrottle = false), limit);
            }
        };
    }

    function getScrollProgress() {
        const scrollTop = window.scrollY || document.documentElement.scrollTop || 0;
        const docHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;
        if (docHeight <= 0) {
            return { scrollPercentage: 0, scrollY: 0 };
        }
        const percentage = Math.min(1, Math.max(0, scrollTop / docHeight));
        return {
            scrollPercentage: parseFloat(percentage.toFixed(4)),
            scrollY: Math.round(scrollTop)
        };
    }

    function reportProgress() {
        const path = getCurrentPath();
        const progress = getScrollProgress();
        fetch(`${apiBase}/stats/reading-progress`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                uri: path,
                scrollPercentage: progress.scrollPercentage,
                scrollY: progress.scrollY,
                clientId: clientId
            }),
            keepalive: true
        }).catch(err => {
            console.debug("[online-monitor] report progress failed", err);
        });
    }

    function reportProgressViaWs() {
        if (!isSocketUsable(socket)) return;
        const progress = getScrollProgress();
        try {
            socket.send(JSON.stringify({
                type: "progress-report",
                scrollPercentage: progress.scrollPercentage,
                scrollY: progress.scrollY
            }));
        } catch (e) {
            console.debug("[online-monitor] WS 上报进度失败", e);
        }
    }

    function installWsMessageHandler(ws) {
        ws.onmessage = function (event) {
            if (socket !== ws) return;
            try {
                const msg = JSON.parse(event.data);
                if (msg.type === "progress") {
                    renderProgressIndicators(msg.list || []);
                    window.dispatchEvent(new CustomEvent("online-monitor:progress-updated", {
                        detail: {
                            uri: getCurrentPath(),
                            progressList: msg.list || []
                        }
                    }));
                }
            } catch (e) {
                console.debug("[online-monitor] 收到非 JSON 消息（旧版兼容）:", event.data);
            }
        };
    }

    function createProgressContainer() {
        let container = document.getElementById("online-reading-progress-container");
        if (!container) {
            container = document.createElement("div");
            container.id = "online-reading-progress-container";
            container.style.cssText = `
                position: fixed;
                right: 4px;
                top: 0;
                bottom: 0;
                width: 6px;
                z-index: 99999;
                pointer-events: none;
                transition: opacity 0.3s;
            `;
            document.body.appendChild(container);
        }
        return container;
    }

    function renderProgressIndicators(progressList) {
        const container = createProgressContainer();
        // 清空旧节点
        while (container.firstChild) {
            container.removeChild(container.firstChild);
        }
        progressIndicators = [];

        if (!progressList || progressList.length === 0) {
            container.style.opacity = "0";
            return;
        }

        container.style.opacity = "1";
        progressList.forEach((item, index) => {
            if (typeof item.scrollPercentage !== "number") return;
            const dot = document.createElement("div");
            dot.className = "online-reading-dot";
            const topPct = Math.min(100, Math.max(0, item.scrollPercentage * 100));
            dot.style.cssText = `
                position: absolute;
                right: 0;
                top: ${topPct}%;
                width: 6px;
                height: 6px;
                border-radius: 50%;
                background: hsl(${(index * 47) % 360}, 70%, 55%);
                transform: translateY(-50%);
                box-shadow: 0 0 2px rgba(0,0,0,0.2);
                transition: top 0.6s ease;
            `;
            dot.title = `其他读者 (${Math.round(item.scrollPercentage * 100)}%)`;
            container.appendChild(dot);
            progressIndicators.push(dot);
        });
    }

    function startProgressReporting() {
        // 滚动事件节流上报（HTTP POST 主通道）
        const throttledReport = throttle(reportProgress, PROGRESS_THROTTLE_MS);
        window.addEventListener("scroll", throttledReport, { passive: true });
        // 页面加载后首次上报（延迟等待元素渲染）
        setTimeout(reportProgress, 800);
        // 页面隐藏后恢复时重新上报一次
        document.addEventListener("visibilitychange", function () {
            if (!document.hidden) {
                setTimeout(reportProgress, 300);
            }
        });
    }

    function stopProgressReporting() {
        const container = document.getElementById("online-reading-progress-container");
        if (container) {
            container.style.opacity = "0";
        }
    }

    // 初始化
    installHistoryHooks();
    installRouteListeners();
    connect(true);
    startPathWatcher();
    startProgressReporting();
})();
