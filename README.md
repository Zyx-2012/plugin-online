# Halo Plugin Online Monitor

一个用于 **Halo** 的在线监控插件，提供站点实时在线人数、活跃页面统计、路径热度排行以及后台监控看板。

作者：**Zyx-2012**

---

## 功能特性

- 实时统计全站在线人数
- 统计当前活跃页面数量
- 展示页面 URI 热度排行
- 记录页面最后活跃时间
- 后台提供在线监控看板
- 提供前端可调用的统计 API
- 支持通过设置项控制刷新频率、数据公开性等参数

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

### 获取当前总在线人数

```http
GET /apis/online-user.zyx2012.cn/v1alpha1/stats/total
```

返回示例：

```json
{
  "total": 12
}
```

---

## 主题开发者调用示例

### 方案一：通过标准 API 获取总在线人数

```js
fetch('/apis/online-user.zyx2012.cn/v1alpha1/stats/total')
  .then(res => res.json())
  .then(data => {
    document.getElementById('online-count').innerText = data.total;
  });
```

HTML 示例：

```html
<span id="online-count">0</span> 人在线
```

### 方案二：自行请求统计列表

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

* **清理频率**：超过指定时间未收到心跳时，将会话视为失活
* **数据公开性**：是否允许前端公开获取详细路径分布
* **控制台刷新率**：后台看板轮询接口的时间间隔

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

### 后端

服务端主要包含：

* `PageOnlineHandler`：WebSocket 在线会话处理
* `OnlineController`：统计接口
* `OnlineFooterProcessor`：前端脚本注入

---

## 使用场景

* 站长查看当前站点实时访问情况
* 快速发现当前热门页面
* 为主题或前端组件提供在线人数展示能力
* 作为轻量级在线监控插件使用

---

## 注意事项

* 当前“过去 24 小时峰值”为**内存态统计**，插件或宿主重启后会重新累计
* 页面在线数据依赖前端 WebSocket 连接与心跳维持
* 如果关闭详细数据公开，建议前端仅调用 `/stats/total`

---

## 计划中的改进

* 设置项真正驱动后端行为
* 支持更完善的会话失活清理策略
* 支持主题模板变量注入
* 提供更多统计维度和图表展示
* 优化后台与 Halo 原生界面风格的一致性

---

## 许可证

本项目由 **Zyx-2012** 开发与维护。
许可证请以仓库中的 `LICENSE` 文件为准。

---

## 反馈

如果你在使用过程中发现问题或有功能建议，欢迎提交 Issue 或反馈交流。
