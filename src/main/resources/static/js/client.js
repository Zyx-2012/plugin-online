(function () {
    const wsProtocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = `${wsProtocol}//${window.location.host}/apis/online-user.zyx2012.cn/v1alpha1/online-ws`;

    let socket = null;
    let heartbeatTimer = null;
    let pathnameWatcher = null;
    let currentPath = null;
    let reconnectTimer = null;
    let manualClose = false;

    function getCurrentPath() {
        return window.location.pathname || "/";
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

    function startHeartbeat() {
        clearHeartbeat();
        heartbeatTimer = setInterval(() => {
            if (socket && socket.readyState === WebSocket.OPEN) {
                socket.send("ping");
            }
        }, 30000);
    }

    function closeSocket() {
        clearHeartbeat();
        if (socket) {
            try {
                manualClose = true;
                socket.close();
            } catch (e) {
                console.debug("[online-monitor] close socket failed", e);
            }
            socket = null;
        }
    }

    function connect(force) {
        const nextPath = getCurrentPath();

        if (!force && socket && socket.readyState === WebSocket.OPEN && currentPath === nextPath) {
            return;
        }

        closeSocket();
        clearReconnect();

        currentPath = nextPath;
        manualClose = false;
        socket = new WebSocket(wsUrl);

        socket.onopen = function () {
            socket.send(currentPath);
            startHeartbeat();
        };

        socket.onclose = function () {
            clearHeartbeat();
            socket = null;

            if (!manualClose) {
                reconnectTimer = setTimeout(() => {
                    connect(true);
                }, 1500);
            }
        };

        socket.onerror = function () {
            // 交给 onclose 统一重连
        };
    }

    function refreshIfPathChanged() {
        const nextPath = getCurrentPath();
        if (nextPath !== currentPath) {
            connect(true);
        }
    }

    function installHistoryHooks() {
        const rawPushState = history.pushState;
        const rawReplaceState = history.replaceState;

        history.pushState = function () {
            const result = rawPushState.apply(this, arguments);
            setTimeout(refreshIfPathChanged, 0);
            return result;
        };

        history.replaceState = function () {
            const result = rawReplaceState.apply(this, arguments);
            setTimeout(refreshIfPathChanged, 0);
            return result;
        };
    }

    function installRouteListeners() {
        window.addEventListener("popstate", refreshIfPathChanged);
        window.addEventListener("hashchange", refreshIfPathChanged);

        window.addEventListener("pageshow", function () {
            // 兼容浏览器前进/后退缓存恢复
            connect(true);
        });

        document.addEventListener("visibilitychange", function () {
            if (!document.hidden) {
                refreshIfPathChanged();
                if (!socket || socket.readyState !== WebSocket.OPEN) {
                    connect(true);
                }
            }
        });
    }

    function startPathWatcher() {
        if (pathnameWatcher) {
            clearInterval(pathnameWatcher);
        }
        pathnameWatcher = setInterval(refreshIfPathChanged, 1000);
    }

    installHistoryHooks();
    installRouteListeners();
    connect(true);
    startPathWatcher();
})();
