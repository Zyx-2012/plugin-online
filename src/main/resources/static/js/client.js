(function () {
    const wsProtocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = `${wsProtocol}//${window.location.host}/apis/online-user.zyx2012.cn/v1alpha1/online-ws`;

    let socket = null;
    let heartbeatTimer = null;
    let pathnameWatcher = null;
    let currentPath = null;
    let reconnectTimer = null;

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

    // 初始化
    installHistoryHooks();
    installRouteListeners();
    connect(true);
    startPathWatcher();
})();
