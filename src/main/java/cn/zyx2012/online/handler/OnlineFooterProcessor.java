package cn.zyx2012.online.handler;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.theme.dialect.TemplateFooterProcessor;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;

@Component
public class OnlineFooterProcessor implements TemplateFooterProcessor {

    private static final Object PUBLIC_MARKER = "public";
    private final PageOnlineHandler handler;

    public OnlineFooterProcessor(PageOnlineHandler handler) {
        this.handler = handler;
    }

    @Override
    public Mono<Void> process(ITemplateContext context,
        IProcessableElementTag tag,
        IElementTagStructureHandler structureHandler,
        IModel model) {

        var factory = context.getModelFactory();
        boolean privatePage = isPrivatePage(context);
        PageOnlineHandler.BasicSetting setting = handler.getBasicSettingSnapshot();

        String script = """
            <script>
            window.__ONLINE_MONITOR_META__ = Object.assign({}, window.__ONLINE_MONITOR_META__, {
                privatePage: %s,
                rateLimitInterval: %s,
                readingProgressEnabled: %s,
                readingProgressInterval: %d
            });
            </script>
            <script src="/plugins/online/assets/static/js/client.js"></script>
            """.formatted(
                privatePage,
                setting.normalizedRateLimitInterval(),
                setting.isReadingProgressEnabled(),
                setting.normalizedReadingProgressInterval()
            );

        model.add(factory.createText("\n" + script + "\n"));
        return Mono.empty();
    }

    private boolean isPrivatePage(ITemplateContext context) {
        VisibilityState postVisibility = resolveVisibility(context.getVariable("post"));
        VisibilityState singlePageVisibility = resolveVisibility(context.getVariable("singlePage"));
        VisibilityState sheetVisibility = resolveVisibility(context.getVariable("sheet"));
        VisibilityState contentVisibility = resolveVisibility(context.getVariable("content"));
        VisibilityState pageVisibility = resolveVisibility(context.getVariable("page"));

        if (postVisibility == VisibilityState.RESTRICTED
            || singlePageVisibility == VisibilityState.RESTRICTED
            || sheetVisibility == VisibilityState.RESTRICTED
            || contentVisibility == VisibilityState.RESTRICTED
            || pageVisibility == VisibilityState.RESTRICTED) {
            return true;
        }

        return postVisibility == VisibilityState.UNKNOWN
            || singlePageVisibility == VisibilityState.UNKNOWN
            || sheetVisibility == VisibilityState.UNKNOWN
            || contentVisibility == VisibilityState.UNKNOWN
            || pageVisibility == VisibilityState.UNKNOWN;
    }

    private VisibilityState resolveVisibility(Object source) {
        if (source == null) {
            return VisibilityState.ABSENT;
        }

        for (Object value : new Object[]{
            readPath(source, "spec", "visible"),
            readPath(source, "spec", "visibility"),
            readPath(source, "status", "visible"),
            readPath(source, "status", "visibility"),
            readPath(source, "visible"),
            readPath(source, "visibility"),
            readPath(source, "password"),
            readPath(source, "spec", "password"),
            readPath(source, "status", "password"),
            readPath(source, "spec", "passwordProtected"),
            readPath(source, "status", "passwordProtected")
        }) {
            VisibilityState state = toVisibilityState(value);
            if (state != VisibilityState.ABSENT) {
                return state;
            }
        }

        return VisibilityState.UNKNOWN;
    }

    private Object readPath(Object source, String... path) {
        Object current = source;
        for (String segment : path) {
            current = readProperty(current, segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private Object readProperty(Object source, String property) {
        if (source == null) {
            return null;
        }

        if (source instanceof Map<?, ?> map) {
            return map.get(property);
        }

        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (String methodName : new String[]{"get" + suffix, "is" + suffix, property}) {
            try {
                Method method = source.getClass().getMethod(methodName);
                return method.invoke(source);
            } catch (Exception ignored) {
                // Continue trying alternative access patterns.
            }
        }
        return null;
    }

    private VisibilityState toVisibilityState(Object value) {
        if (value == null) {
            return VisibilityState.ABSENT;
        }

        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return VisibilityState.ABSENT;
        }
        if (String.valueOf(PUBLIC_MARKER).equals(normalized)
            || normalized.contains("public")
            || "false".equals(normalized)) {
            return VisibilityState.PUBLIC;
        }
        if (normalized.contains("private")
            || normalized.contains("password")
            || normalized.contains("protected")
            || "true".equals(normalized)) {
            return VisibilityState.RESTRICTED;
        }
        return VisibilityState.UNKNOWN;
    }

    private enum VisibilityState {
        ABSENT,
        PUBLIC,
        RESTRICTED,
        UNKNOWN
    }
}
