package com.blade.server.netty;

import com.blade.Blade;
import com.blade.Environment;
import com.blade.event.BeanProcessor;
import com.blade.event.EventType;
import com.blade.ioc.DynamicContext;
import com.blade.ioc.Ioc;
import com.blade.ioc.annotation.Bean;
import com.blade.ioc.bean.BeanDefine;
import com.blade.ioc.bean.ClassInfo;
import com.blade.ioc.bean.OrderComparator;
import com.blade.kit.BladeKit;
import com.blade.kit.NamedThreadFactory;
import com.blade.kit.ReflectKit;
import com.blade.kit.StringKit;
import com.blade.mvc.Const;
import com.blade.mvc.WebContext;
import com.blade.mvc.annotation.Path;
import com.blade.mvc.annotation.UrlPattern;
import com.blade.mvc.handler.DefaultExceptionHandler;
import com.blade.mvc.handler.ExceptionHandler;
import com.blade.mvc.hook.WebHook;
import com.blade.mvc.route.RouteBuilder;
import com.blade.mvc.route.RouteMatcher;
import com.blade.mvc.ui.template.DefaultEngine;
import com.blade.server.Server;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ResourceLeakDetector;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.blade.mvc.Const.*;
import static com.blade.server.netty.HttpConst.BUSINESS_THREAD_POOL;

/**
 * @author biezhi
 * 2017/5/31
 */
@Slf4j
public class NettyServer implements Server {

    private Blade               blade;
    private Environment         environment;
    private EventLoopGroup      bossGroup;
    private EventLoopGroup      workerGroup;
    private int                 threadCount;
    private int                 workers;
    private int                 backlog;
    private Channel             channel;
    private RouteBuilder        routeBuilder;
    private List<BeanProcessor> processors;

    @Override
    public void start(Blade blade, String[] args) throws Exception {
        this.blade = blade;
        this.environment = blade.environment();
        this.processors = blade.processors();

        long initStart = System.currentTimeMillis();
        log.info("Environment: jdk.version    => {}", System.getProperty("java.version"));
        log.info("Environment: user.dir       => {}", System.getProperty("user.dir"));
        log.info("Environment: java.io.tmpdir => {}", System.getProperty("java.io.tmpdir"));
        log.info("Environment: user.timezone  => {}", System.getProperty("user.timezone"));
        log.info("Environment: file.encoding  => {}", System.getProperty("file.encoding"));
        log.info("Environment: classpath      => {}", CLASSPATH);

        this.loadConfig(args);
        this.initConfig();

        WebContext.init(blade, "/");

        this.initIoc();

        this.shutdownHook();

        this.startServer(initStart);

    }

    private void initIoc() {
        RouteMatcher routeMatcher = blade.routeMatcher();
        routeMatcher.initMiddleware(blade.middleware());

        routeBuilder = new RouteBuilder(routeMatcher);

        blade.scanPackages().stream()
                .flatMap(DynamicContext::recursionFindClasses)
                .map(ClassInfo::getClazz)
                .filter(ReflectKit::isNormalClass)
                .forEach(this::parseCls);

        routeBuilder.register();

        this.processors.stream().sorted(new OrderComparator<>()).forEach(b -> b.preHandle(blade));

        Ioc ioc = blade.ioc();
        if (BladeKit.isNotEmpty(ioc.getBeans())) {
            log.info("⬢ Register bean: {}", ioc.getBeans());
        }

        List<BeanDefine> beanDefines = ioc.getBeanDefines();
        if (BladeKit.isNotEmpty(beanDefines)) {
            beanDefines.forEach(b -> BladeKit.injection(ioc, b));
        }

        this.processors.stream().sorted(new OrderComparator<>()).forEach(b -> b.processor(blade));

    }

    private void startServer(long startTime) throws Exception {

        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);

        boolean SSL = environment.getBoolean(ENV_KEY_SSL, false);
        // Configure SSL.
        SslContext sslCtx = null;
        if (SSL) {
            String certFilePath       = environment.get(ENV_KEY_SSL_CERT, null);
            String privateKeyPath     = environment.get(ENE_KEY_SSL_PRIVATE_KEY, null);
            String privateKeyPassword = environment.get(ENE_KEY_SSL_PRIVATE_KEY_PASS, null);

            log.info("⬢ SSL CertChainFile  Path: {}", certFilePath);
            log.info("⬢ SSL PrivateKeyFile Path: {}", privateKeyPath);
            sslCtx = SslContextBuilder.forServer(new File(certFilePath), new File(privateKeyPath), privateKeyPassword).build();
        }

        // Configure the server.
        ServerBootstrap b = new ServerBootstrap();
        b.option(ChannelOption.SO_BACKLOG, backlog);
        b.option(ChannelOption.SO_REUSEADDR, true);
        b.childOption(ChannelOption.SO_REUSEADDR, true);

        // enable epoll
        if (BladeKit.epollIsAvailable()) {
            log.info("⬢ Use EpollEventLoopGroup");

            b.option(EpollChannelOption.SO_REUSEPORT, true);

            NettyServerGroup nettyServerGroup = EpollKit.group(threadCount, workers);
            this.bossGroup = nettyServerGroup.getBoosGroup();
            this.workerGroup = nettyServerGroup.getWorkerGroup();
            b.group(bossGroup, workerGroup).channel(nettyServerGroup.getSocketChannel());
        } else {
            log.info("⬢ Use NioEventLoopGroup");
            this.bossGroup = new NioEventLoopGroup(threadCount, new NamedThreadFactory("nio-boss@"));
            this.workerGroup = new NioEventLoopGroup(workers, new NamedThreadFactory("nio-worker@"));
            b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class);
        }

        b.handler(new LoggingHandler(LogLevel.DEBUG))
                .childHandler(new HttpServerInitializer(sslCtx, blade, bossGroup.next()));

        String address = environment.get(ENV_KEY_SERVER_ADDRESS, DEFAULT_SERVER_ADDRESS);
        int    port    = environment.getInt(ENV_KEY_SERVER_PORT, DEFAULT_SERVER_PORT);

        channel = b.bind(address, port).sync().channel();
        String appName = environment.get(ENV_KEY_APP_NAME, "Blade");

        log.info("⬢ {} initialize successfully, Time elapsed: {} ms", appName, (System.currentTimeMillis() - startTime));
        log.info("⬢ Blade start with {}:{}", address, port);
        log.info("⬢ Open your web browser and navigate to {}://{}:{} ⚡", "http", address.replace(DEFAULT_SERVER_ADDRESS, LOCAL_IP_ADDRESS), port);

        blade.eventManager().fireEvent(EventType.SERVER_STARTED, blade);
    }


    private void parseCls(Class<?> clazz) {
        if (null != clazz.getAnnotation(Bean.class)) blade.register(clazz);
        if (null != clazz.getAnnotation(Path.class)) {
            if (null == blade.ioc().getBean(clazz)) {
                blade.register(clazz);
            }
            Object controller = blade.ioc().getBean(clazz);
            routeBuilder.addRouter(clazz, controller);
        }
        if (ReflectKit.hasInterface(clazz, WebHook.class) && null != clazz.getAnnotation(Bean.class)) {
            Object     hook       = blade.ioc().getBean(clazz);
            UrlPattern urlPattern = clazz.getAnnotation(UrlPattern.class);
            if (null == urlPattern) {
                routeBuilder.addWebHook(clazz, "/.*", hook);
            } else {
                Stream.of(urlPattern.values())
                        .forEach(pattern -> routeBuilder.addWebHook(clazz, pattern, hook));
            }
        }
        if (ReflectKit.hasInterface(clazz, BeanProcessor.class) && null != clazz.getAnnotation(Bean.class)) {
            this.processors.add((BeanProcessor) blade.ioc().getBean(clazz));
        }
        if (isExceptionHandler(clazz)) {
            ExceptionHandler exceptionHandler = (ExceptionHandler) blade.ioc().getBean(clazz);
            blade.exceptionHandler(exceptionHandler);
        }
    }

    private boolean isExceptionHandler(Class<?> clazz) {
        return (null != clazz.getAnnotation(Bean.class) && (
                ReflectKit.hasInterface(clazz, ExceptionHandler.class) || clazz.getSuperclass().equals(DefaultExceptionHandler.class)));
    }

    private void loadConfig(String[] args) {

        String bootConf = blade.environment().get(ENV_KEY_BOOT_CONF, "classpath:app.properties");

        Environment bootEnv = Environment.of(bootConf);

        if (bootEnv != null) {
            bootEnv.props().forEach((key, value) -> environment.set(key.toString(), value));
        }

        if (null != args) {
            Optional<String> envArg = Stream.of(args).filter(s -> s.startsWith(Const.TERMINAL_BLADE_ENV)).findFirst();
            envArg.ifPresent(arg -> {
                String envName = "app-" + arg.split("=")[1] + ".properties";
                log.info("current environment file is: {}", envName);
                Environment customEnv = Environment.of(envName);
                if (customEnv != null) {
                    customEnv.props().forEach((key, value) -> environment.set(key.toString(), value));
                }
            });
        }

        blade.register(environment);

        // load terminal param
        if (!BladeKit.isEmpty(args)) {
            for (String arg : args) {
                if (arg.startsWith(TERMINAL_SERVER_ADDRESS)) {
                    int    pos     = arg.indexOf(TERMINAL_SERVER_ADDRESS) + TERMINAL_SERVER_ADDRESS.length();
                    String address = arg.substring(pos);
                    environment.set(ENV_KEY_SERVER_ADDRESS, address);
                } else if (arg.startsWith(TERMINAL_SERVER_PORT)) {
                    int    pos  = arg.indexOf(TERMINAL_SERVER_PORT) + TERMINAL_SERVER_PORT.length();
                    String port = arg.substring(pos);
                    environment.set(ENV_KEY_SERVER_PORT, port);
                }
            }
        }
    }

    private void initConfig() {

        if (null != blade.bootClass()) {
            blade.scanPackages(blade.bootClass().getPackage().getName());
        }

        // print banner text
        this.printBanner();

        String statics = environment.get(ENV_KEY_STATIC_DIRS, "");
        if (StringKit.isNotBlank(statics)) {
            blade.addStatics(statics.split(","));
        }

        String templatePath = environment.get(ENV_KEY_TEMPLATE_PATH, "templates");
        if (templatePath.charAt(0) == HttpConst.CHAR_SLASH) {
            templatePath = templatePath.substring(1);
        }
        if (templatePath.endsWith(HttpConst.SLASH)) {
            templatePath = templatePath.substring(0, templatePath.length() - 1);
        }
        DefaultEngine.TEMPLATE_PATH = templatePath;

        threadCount = environment.getInt(ENV_KEY_NETTY_THREAD_COUNT, 1);
        workers = environment.getInt(ENV_KEY_NETTY_WORKERS, 0);
        backlog = environment.getInt(ENV_KEY_NETTY_SO_BACKLOG, 8192);
    }

    private void shutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    @Override
    public void stop() {
        log.info("⬢ Blade shutdown ...");
        try {
            this.bossGroup.shutdownGracefully();
            this.workerGroup.shutdownGracefully();
            BUSINESS_THREAD_POOL.shutdown();
            log.info("⬢ Blade shutdown successful");
        } catch (Exception e) {
            log.error("Blade shutdown error", e);
        }
    }

    @Override
    public void stopAndWait() {
        log.info("⬢ Blade shutdown ...");
        try {
            this.bossGroup.shutdownGracefully().sync();
            this.workerGroup.shutdownGracefully().sync();
            BUSINESS_THREAD_POOL.shutdown();
            log.info("⬢ Blade shutdown successful");
        } catch (Exception e) {
            log.error("Blade shutdown error", e);
        }
    }

    @Override
    public void join() throws InterruptedException {
        channel.closeFuture().sync();
    }

    /**
     * print blade start banner text
     */
    private void printBanner() {
        if (null != blade.bannerText()) {
            System.out.println(blade.bannerText());
        } else {
            StringBuilder text = new StringBuilder();
            text.append(Const.BANNER_TEXT);
            text.append("\r\n")
                    .append(BANNER_SPACE)
                    .append(" :: Blade :: (v")
                    .append(Const.VERSION + ") \r\n");
            System.out.println(text.toString());
        }
    }

}
