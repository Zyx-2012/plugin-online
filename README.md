# Halo Plugin Online

![Logo](https://github.com/Zyx-2012/plugin-online/blob/main/src/main/resources/logo.png)

一个用于 **Halo** 的在线监控插件，提供站点实时在线人数、活跃页面统计、路径热度排行以及后台监控看板。

作者：**Zyx-2012**

[关于该插件，我的博客](https://blog.zyx-2012.cn/archives/1775566651829)

---

## 功能特性

- 实时统计全站在线人数
- 统计当前活跃页面数量
- 展示页面 URI 热度排行
- 记录页面最后活跃时间
- 后台提供在线监控看板
- 提供前端可调用的统计 API
- 支持通过设置项控制会话失活时间、路径公开范围、控制台刷新频率等参数

---

## 界面预览

插件后台提供一个“在线监控看板”页面，主要包含：

- 当前全站总计
- 过去 24 小时峰值
- 活跃页面数
- 实时路径热度榜

---

## 工作原理

插件主要由以下部分组成：

### 1. 前台注入脚本
通过 `TemplateFooterProcessor` 在主题页脚注入脚本，前端页面加载后会自动建立 WebSocket 连接。

### 2. WebSocket 在线会话统计
前端会将当前页面路径发送到后端，后端按 URI 维护在线会话，并实时更新当前页面在线人数。

### 3. 后台监控看板
插件后台页面会定时拉取统计接口，展示实时在线数据。

---

## API

### 获取全部页面统计

```http
GET /apis/online-user.zyx2012.cn/v1alpha1/stats
````

返回示例：

```json
[
  {
    "uri": "/",
    "count": 12,
    "lastActiveAt": "2025-08-04T12:34:56Z",
    "viewUrl": "/"
  }
]
```

### 获取总览统计

```http
GET /apis/online-user.zyx2012.cn/v1alpha1/stats/summary
```

返回示例：

```json
{
  "total": 12,
  "peak24h": 35,
  "activePages": 4,
  "updatedAt": "2025-08-04T12:34:56Z",
  "wsActive": true
}
```

---

## 主题开发者调用示例

### 方案一：通过总览接口获取总在线人数（推荐）

```js
fetch('/apis/online-user.zyx2012.cn/v1alpha1/stats/summary')
  .then(res => res.json())
  .then(data => {
    document.getElementById('online-count').innerText = data.total;
  });
```

HTML 示例：

```html
<span id="online-count">0</span> 人在线
```

### 方案二：通过总览接口获取更多实时信息

```js
fetch('/apis/online-user.zyx2012.cn/v1alpha1/stats/summary')
  .then(res => res.json())
  .then(data => {
    console.log('当前总在线人数：', data.total);
    console.log('当前活跃页面数：', data.activePages);
    console.log('过去 24 小时峰值：', data.peak24h);
  });
```

### 方案三：自行请求统计列表

```js
fetch('/apis/online-user.zyx2012.cn/v1alpha1/stats')
  .then(res => res.json())
  .then(data => {
    console.log('实时页面在线数据', data);
  });
```

---

## 插件设置

插件支持以下设置项：

* **会话失活时间**：服务端超过指定时间未收到心跳后清理失活会话
* **公开详细路径**：是否允许匿名请求获取路径明细，私有页路径始终会脱敏
* **控制台刷新率**：后台看板轮询接口的时间间隔
* **WebSocket 来源白名单**：允许哪些域名、IP 或来源连接在线统计 WebSocket

---

## 开发说明

### 前端

插件后台 UI 使用 **Vite** 构建，主要目录如下：

```text
ui/
├── assets/
├── views/
│   └── MonitorView.vue
├── api.ts
├── index.ts
├── styles.css
└── types.ts
```

插件前台脚本通过 `TemplateFooterProcessor` 注入到主题页面，用于建立 WebSocket 连接并同步当前页面路径。

前台脚本需要处理以下场景：

* 首次加载时建立 WebSocket 连接
* WebSocket `open` 后发送当前 `pathname` 和私有页标记
* PJAX / 无刷新切页后重新同步路径
* 浏览器前进后退缓存恢复后重新同步状态
* 连接断开后自动重连，并避免在 `pageshow` / `visibilitychange` 下重复重连
* 在当前页面注册完成后，主动派发前端事件通知主题组件刷新统计

当前脚本会派发以下事件：

* `online-monitor:registered`：当前页面已完成注册
* `online-monitor:path-changed`：检测到页面路径变化

如果主题中有侧边栏在线统计卡片，建议监听 `online-monitor:registered` 事件后重新请求 `/stats/summary`，这样可以避免页面刷新后由于 WebSocket 还未完成首条路径上报，导致前台短暂显示 `0` 的情况。

示例：

```js
window.addEventListener("online-monitor:registered", function () {
  loadData();
});
```

### 后端

服务端主要包含：

* `PageOnlineHandler`：WebSocket 在线会话处理
* `OnlineController`：统计接口
* `OnlineFooterProcessor`：前端脚本注入

`PageOnlineHandler` 负责：

* 校验 WebSocket 来源
* 规范化并校验客户端上报路径
* 记录页面与在线会话的映射关系
* 更新页面最后活跃时间
* 根据设置项清理失活会话并对私有或受保护页面做隐藏保护
* 统计当前总在线人数、活跃页面数、24 小时峰值

如果站点存在以下部署链路：

* 内网 IP 访问
* FRP 转发
* Cloudflare 代理
* 多域名访问

建议在插件设置中正确配置 **WebSocket 来源白名单**，否则可能出现 `client.js` 已加载但 WebSocket 握手被拒绝，导致无法统计在线人数的问题。

---

## 使用场景

* 站长查看当前站点实时访问情况
* 快速发现当前热门页面
* 为主题或前端组件提供在线人数展示能力
* 作为轻量级在线监控插件使用

---

## 主题兼容说明

本插件通过 `TemplateFooterProcessor` 向主题页面注入前端脚本，因此主题模板中必须包含：

```html
<halo:footer />
```

通常建议将该标签放在主题公共布局文件的 `</body>` 之前，例如 `layout.html` 中。

如果主题缺少 `<halo:footer />`，插件的前端脚本将无法注入，可能会出现以下问题：

* 页面未加载 `client.js`
* WebSocket 未建立连接
* 在线人数无法统计
* 前台相关功能失效

如果你在更换主题后发现插件没有生效，请优先检查当前主题的公共布局文件中是否包含 `<halo:footer />`。

---

## 注意事项

* 当前“过去 24 小时峰值”为**内存态统计**，插件或宿主重启后会重新累计
* 页面在线数据依赖前端 WebSocket 连接与心跳维持

---

## 计划中的改进

* 提供更多统计维度和图表展示
* 优化后台与 Halo 原生界面风格的一致性

---

## 许可证

本项目由 **Zyx-2012** 开发与维护。
许可证请以仓库中的 `LICENSE` 文件为准。

---

## 反馈

如果你在使用过程中发现问题或有功能建议，欢迎提交 Issue 或反馈交流。
