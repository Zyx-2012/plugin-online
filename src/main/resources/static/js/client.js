(function () {
    const path = window.location.pathname;
    const wsProtocol = window.location.protocol === "https:" ? "wss:" : "ws:";

    const socket = new WebSocket(
        `${wsProtocol}//${window.location.host}/apis/online-user.zyx2012.cn/v1alpha1/online-ws`
    );

    socket.onopen = function () {
        socket.send(path);
    };

    const heartbeat = setInterval(() => {
        if (socket.readyState === WebSocket.OPEN) {
            socket.send("ping");
        }
    }, 30000);

    socket.onclose = function () {
        clearInterval(heartbeat);
    };
})();
