package org.genva.ssrpoc;

import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.ui.ModelMap;
import org.springframework.util.MimeType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;
import org.springframework.web.servlet.view.AbstractCachingViewResolver;
import org.springframework.web.servlet.view.ContentNegotiatingViewResolver;
import org.springframework.web.servlet.view.json.MappingJackson2JsonView;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.dialect.AbstractProcessorDialect;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.exceptions.TemplateProcessingException;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.IProcessor;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.spring4.SpringTemplateEngine;
import org.thymeleaf.spring4.view.ThymeleafView;
import org.thymeleaf.spring4.view.ThymeleafViewResolver;
import org.thymeleaf.templatemode.TemplateMode;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootApplication
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Bean
    public ContentNegotiatingViewResolver viewResolver(
            ThymeleafViewResolver thymeleafViewResolver,
            JsonViewResolver jsonViewResolver) {
        ContentNegotiatingViewResolver resolver = new ContentNegotiatingViewResolver();
        resolver.setViewResolvers(Arrays.asList(thymeleafViewResolver, jsonViewResolver));
        return resolver;
    }

    @Bean
    public ThymeleafViewResolver thymeleafViewResolver(ThymeleafProperties properties,
            SpringTemplateEngine templateEngine) {
        ThymeleafViewResolver resolver = new ThymeleafViewResolver();
        resolver.setTemplateEngine(templateEngine);
        resolver.setCharacterEncoding(properties.getEncoding().name());
        resolver.setContentType(appendCharset(properties.getContentType(),
                resolver.getCharacterEncoding()));
        resolver.setExcludedViewNames(properties.getExcludedViewNames());
        resolver.setViewNames(properties.getViewNames());
        // This resolver acts as a fallback resolver (e.g. like a
        // InternalResourceViewResolver) so it needs to have low precedence
        resolver.setOrder(Ordered.LOWEST_PRECEDENCE - 5);
        resolver.setCache(properties.isCache());
        resolver.setViewClass(ServerJsThymeleafView.class);  // これがやりたかっただけ
        return resolver;
    }

    private String appendCharset(MimeType type, String charset) {
        if (type.getCharset() != null) {
            return type.toString();
        }
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        parameters.put("charset", charset);
        parameters.putAll(type.getParameters());
        return new MimeType(type, parameters).toString();
    }

    public static class ServerJsThymeleafView extends ThymeleafView {
        public static final String KEY_PROPS = "props";
        public static final String KEY_REACT_COMPONENT = "react_component";

        private ReloadableScriptEngineFactory engineFactory;
        private ReactComponent react;

        @Override
        protected void initApplicationContext(ApplicationContext context) {
            super.initApplicationContext(context);
            engineFactory = context.getBean(ReloadableScriptEngineFactory.class);
            react = new ReactComponent();
        }

        @Override
        public void render(Map<String, ?> model, HttpServletRequest request, HttpServletResponse response)
                throws Exception {
            RelodableScriptEngine engine = engineFactory.getScriptEngine().reload().newBindings();
            model = renderReactComponent(model, engine.get());
            super.render(model, request, response);
        }

        private Map<String, ?> renderReactComponent(Map<String, ?> model, ScriptEngine engine) throws Exception {
            Map<String, Object> map = new HashMap<>();
            if (model != null)
                map.putAll(model);

            String html = react.render(engine, "Router", (String) map.get(KEY_PROPS));
            map.put(KEY_REACT_COMPONENT, html);

            return map;
        }
    }

    public static class ReactComponent {
        public String render(ScriptEngine engine, String component, String props) throws Exception {
            Object html = engine.eval("render('" + component + "', " + props + ")");
            return String.valueOf(html);
        }
    }

    @Bean
    public JsonViewResolver jsonViewResolver(ObjectMapper mapper) {
        return new JsonViewResolver(mapper);
    }

    @Bean
    public ReloadableScriptEngineFactory reloadableScriptEngineFactory() {
        ReloadableScriptEngineFactory factory = new ReloadableScriptEngineFactory();
        factory.addScript("classpath:/static/js/polyfill.js");
        factory.addScript("classpath:/static/js/server.bundle.js");
        return factory;
    }

    @Bean
    public ServerJsDialect serverJsDialect(ReloadableScriptEngineFactory engineFactory) {
        return new ServerJsDialect(engineFactory);
    }

    public static class ServerJsDialect extends AbstractProcessorDialect {
        private ReloadableScriptEngineFactory engineFactory;

        public ServerJsDialect(ReloadableScriptEngineFactory engineFactory) {
            super("ServerJsDialect", "serverjs", 1000);
            this.engineFactory = engineFactory;
        }

        @Override
        public Set<IProcessor> getProcessors(String dialectPrefix) {
            Set<IProcessor> procs = new HashSet<>();
            procs.add(new ServerJsReplaceAttrProcessor(engineFactory, dialectPrefix));
            return procs;
        }
    }

    public static class ServerJsReplaceAttrProcessor extends AbstractAttributeTagProcessor {
        private static final String ATTR_NAME = "replace";
        private static final int PRECEDENCE = 10000;

        private ReloadableScriptEngineFactory engineFactory;

        public ServerJsReplaceAttrProcessor(ReloadableScriptEngineFactory engineFactory, String dialectPrefix) {
            super(TemplateMode.HTML,  // this processor will apply only to HTML mode
                    dialectPrefix,    // prefix to be applied to name for matching
                    null,             // no tag name: match any tag name
                    false,            // no prefix to be applied to tag name
                    ATTR_NAME,        // name of the attribute that will be matched
                    true,             // apply dialect prefix to attribute name
                    PRECEDENCE,       // precedence (inside dialect's precedence)
                    true);            // remove the matched attribute afterwards

            this.engineFactory = engineFactory;
        }

        @Override
        protected void doProcess(ITemplateContext context, IProcessableElementTag tag, AttributeName attributeName,
                String attributeValue, IElementTagStructureHandler structureHandler) {
            try {
                RelodableScriptEngine engine = engineFactory.getScriptEngine();
                Object val = engine.get().eval(attributeValue);
                structureHandler.replaceWith(String.valueOf(val), false);
            }
            catch (Exception e) {
                throw new TemplateProcessingException("failed to eval js: '" + attributeValue + "'", e);
            }
        }
    }

    public static class ReloadableScriptEngineFactory implements ApplicationContextAware {

        private final List<String> scripts = new ArrayList<>();
        private final ThreadLocal<RelodableScriptEngine> engines = new ThreadLocal<>();
        private String engineName = "nashorn";

        private ApplicationContext context;
        private ScriptEngineManager scriptEngineManager;

        public ReloadableScriptEngineFactory() {
        }

        @Override
        public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
            context = applicationContext;
            scriptEngineManager = new ScriptEngineManager(context.getClassLoader());
        }

        public void addScript(String script) {
            scripts.add(script);
        }

        public RelodableScriptEngine getScriptEngine() throws Exception {
            RelodableScriptEngine e = engines.get();
            if (e != null)
                return e;

            ScriptEngine engine = scriptEngineManager.getEngineByName(engineName);
            e = new RelodableScriptEngine(engine, context);
            scripts.forEach(e::addScript);
            e.reload();  // initial load
            engines.set(e);
            return e;
        }
    }

    public static class RelodableScriptEngine {
        private final List<String> scripts = new ArrayList<>();
        private final Map<String, Long> timestamps = new HashMap<>();
        private ResourceLoader resourceLoader;
        private ScriptEngine engine;
        private Bindings scriptBindings;

        public RelodableScriptEngine(ScriptEngine engine, ResourceLoader resourceLoader) {
            this.engine = engine;
            this.resourceLoader = resourceLoader;
            this.scriptBindings = new SimpleBindings();
        }

        public void addScript(String script) {
            this.scripts.add(script);
            this.timestamps.put(script, -1L);
        }

        public ScriptEngine get() {
            return engine;
        }

        public RelodableScriptEngine reload() throws ScriptException, IOException {
            for (String script : scripts) {
                Resource resource = resourceLoader.getResource(script);
                long lastModified = resource.lastModified();
                if (timestamps.get(script) < lastModified) {
                    engine.eval(new InputStreamReader(resource.getInputStream()), scriptBindings);
                    timestamps.put(script, lastModified);
                }
            }

            return this;
        }

        public RelodableScriptEngine newBindings() {
            this.engine.setBindings(new SimpleBindings(scriptBindings), ScriptContext.ENGINE_SCOPE);
            return this;
        }
    }

    public static class JsonViewResolver extends AbstractCachingViewResolver {
        private final ObjectMapper mapper;

        public JsonViewResolver(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        protected View loadView(String viewName, Locale locale) throws Exception {
            MappingJackson2JsonView view = new MappingJackson2JsonView(mapper);
            view.setContentType(MediaType.APPLICATION_JSON_VALUE);
            return view;
        }
    }

    @Configuration
    public static class WebConfig extends WebMvcConfigurerAdapter {
        @Autowired
        private ObjectMapper mapper;

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new ReactAttrsInterceptor(mapper));
        }
    }

    public static class ReactAttrsInterceptor extends HandlerInterceptorAdapter {
        private ObjectMapper mapper;

        public ReactAttrsInterceptor(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                ModelAndView modelAndView) throws Exception {
            if (modelAndView != null) {
                ModelMap model = modelAndView.getModelMap();
                model.addAttribute("actionPath", buildActionPath(handler, modelAndView));
                if (!isJson(request)) {
                    modelAndView.setViewName("layout");
                    model.addAttribute("props", mapper.writeValueAsString(model));
                }
            }
        }

        private String buildActionPath(Object handler, ModelAndView mav) {
            if (handler instanceof HandlerMethod) {
                return buildActionPath((HandlerMethod) handler);
            }
            else {
                System.err.println("Unsupported handler: " + (handler != null ? handler.getClass() : "null"));
                return "";
            }
        }

        private String buildActionPath(HandlerMethod handler) {
            String typeName = handler.getBeanType().getSimpleName();
            if (typeName.endsWith("Controller"))
                typeName = typeName.substring(0, typeName.length() - "Controller".length());

            String methodName = handler.getMethod().getName();
            return typeName + "#" + methodName;
        }

        private boolean isJson(HttpServletRequest request) {
            String accept = request.getHeader("Accept");
            if (accept != null)
                return accept.contains("application/json");
            else
                return false;
        }
    }

    @Controller
    @RequestMapping("/")
    public static class RootController {
        @GetMapping
        public void index(Model model) {
            model.addAttribute("now", LocalDateTime.now().toString());
        }
    }

    @Controller
    @RequestMapping("/auto")
    public static class AutoController {
        @GetMapping
        public void index(Model model) {
            model.addAttribute("now", LocalDateTime.now().toString());
        }
    }
}
