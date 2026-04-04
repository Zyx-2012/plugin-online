import { definePlugin } from "@halo-dev/console-shared";
import { h, markRaw } from "vue";
import MonitorView from "./views/MonitorView.vue";
import Logo from "./assets/logo.svg";
import "./styles.css";

const MenuIcon = {
  name: "OnlineMonitorMenuIcon",
  render() {
    return h("img", {
      src: Logo,
      alt: "在线监控看板",
      style: {
        width: "1em",
        height: "1em",
        display: "block",
      },
    });
  },
};

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: "Root",
      route: {
        path: "/online-monitor",
        name: "OnlineMonitor",
        component: markRaw(MonitorView),
        meta: {
          title: "在线监控看板",
          searchable: true,
          menu: {
            name: "在线监控看板",
            group: "tool",
            icon: markRaw(MenuIcon),
            priority: 0,
          },
        },
      },
    },
  ],
  extensionPoints: {},
});