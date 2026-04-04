package cn.zyx2012.online.handler;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.theme.dialect.TemplateFooterProcessor;

@Component
public class OnlineFooterProcessor implements TemplateFooterProcessor {

    @Override
    public Mono<Void> process(ITemplateContext context,
        IProcessableElementTag tag,
        IElementTagStructureHandler structureHandler,
        IModel model) {

        var factory = context.getModelFactory();

        String script = "\n<script src=\"/plugins/online/assets/static/js/client.js\"></script>\n";

        model.add(factory.createText(script));
        return Mono.empty();
    }
}