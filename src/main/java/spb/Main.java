package spb;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.layout.TTLLLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

public class Main {
    static org.slf4j.Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        configureLogger();
        int exitCode = new CommandLine(new Spb()).execute(args);
        System.exit(exitCode);
    }

    private static void configureLogger() {
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        TTLLLayout layout = new TTLLLayout();
        layout.setContext(lc);
        layout.start();

        LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<ILoggingEvent>();
        encoder.setContext(lc);
        encoder.setLayout(layout);

        FileAppender<ILoggingEvent> fileAppender = new FileAppender<>();
        fileAppender.setName("file");
        fileAppender.setFile(System.getProperty("user.home") + "/spb.log");
        fileAppender.setContext(lc);
        fileAppender.setEncoder(encoder);
        fileAppender.start();

        Logger rootLogger = lc.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.INFO);
//        rootLogger.detachAppender("console");
        rootLogger.addAppender(fileAppender);

        Logger spb = lc.getLogger("spb");
        spb.setLevel(Level.DEBUG);
    }
}

