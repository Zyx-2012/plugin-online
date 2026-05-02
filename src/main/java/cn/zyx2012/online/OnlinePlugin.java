package cn.zyx2012.online;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Component
@ComponentScan(basePackages = "cn.zyx2012.online")
public class OnlinePlugin extends BasePlugin {

    public OnlinePlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        System.out.println("在线人数统计插件启动成功！");
    }

    @Override
    public void stop() {
        System.out.println("在线人数统计插件停止！");
    }
}