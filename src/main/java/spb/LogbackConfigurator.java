package spb;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.layout.TTLLLayout;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.spi.ContextAwareBase;

public class LogbackConfigurator extends ContextAwareBase implements Configurator {

    public LogbackConfigurator() {
    }

    public ExecutionStatus configure(LoggerContext lc) {
        /**
         * This code will be executed at graalvm build time because
         * the Loggers are created at build time
         */
        addInfo("Setting up default configuration.");

        // same as
        // PatternLayout layout = new PatternLayout();
        // layout.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} -
        // %msg%n");
        TTLLLayout layout = new TTLLLayout();
        layout.setContext(lc);
        layout.start();

        LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<ILoggingEvent>();
        encoder.setContext(lc);
        encoder.setLayout(layout);

        ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<ILoggingEvent>();
        consoleAppender.setContext(lc);
        consoleAppender.setName("console");
        consoleAppender.setEncoder(encoder);
        consoleAppender.start();

        Logger rootLogger = lc.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.INFO);
        rootLogger.addAppender(consoleAppender);

        // let the caller decide
        return ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
    }

}
