/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.logging.log4j2;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.ThresholdFilter;
import org.apache.logging.log4j.message.Message;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.util.SuppressForbidden;
import org.apache.solr.logging.CircularList;
import org.apache.solr.logging.ListenerConfig;
import org.apache.solr.logging.LogWatcher;
import org.apache.solr.logging.LoggerInfo;

@SuppressForbidden(reason = "class is specific to log4j2")
public class Log4j2Watcher extends LogWatcher<LogEvent> {

  private static final String LOG4J2_WATCHER_APPENDER = "Log4j2WatcherAppender";

  @SuppressForbidden(reason = "class is specific to log4j2")
  protected static class Log4j2Appender extends AbstractAppender {

    private Log4j2Watcher watcher;
    private ThresholdFilter filter;
    private Level threshold;

    Log4j2Appender(Log4j2Watcher watcher, ThresholdFilter filter, Level threshold) {
      super(LOG4J2_WATCHER_APPENDER, filter, null);
      this.watcher = watcher;
      this.filter = filter;
      this.threshold = threshold;
    }

    @Override
    public void append(LogEvent logEvent) {
      watcher.add(logEvent, logEvent.getTimeMillis());
    }

    public Level getThreshold() {
      return threshold;
    }

    public void setThreshold(Level threshold) {
      this.threshold = threshold;
      removeFilter(filter);
      filter = ThresholdFilter.createFilter(threshold, Filter.Result.ACCEPT, Filter.Result.DENY);
      addFilter(filter);
    }
  }

  @SuppressForbidden(reason = "class is specific to log4j2")
  protected static class Log4j2Info extends LoggerInfo {
    public Log4j2Info(String name, Level level, boolean isSet) {
      super(name);
      if (level != null) {
        this.level = level.toString();
      }
      this.isSet = isSet;
    }

    private final boolean isSet;

    @Override
    public String getLevel() {
      return (level != null) ? level : null;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public boolean isSet() {
      return isSet;
    }
  }

  public static final Logger log = LogManager.getLogger(Log4j2Watcher.class);

  protected Log4j2Appender appender = null;

  @Override
  public String getName() {
    return "Log4j2";
  }

  @Override
  public List<String> getAllLevels() {
    return Arrays.asList(
        Level.ALL.toString(),
        Level.TRACE.toString(),
        Level.DEBUG.toString(),
        Level.INFO.toString(),
        Level.WARN.toString(),
        Level.ERROR.toString(),
        Level.FATAL.toString(),
        Level.OFF.toString());
  }

  @Override
  public void setLogLevel(String loggerName, String level) {
    assert loggerName != null;
    assert level != null;
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    Configuration config = ctx.getConfiguration();
    LoggerConfig loggerConfig = getLoggerConfig(ctx, loggerName);
    assert loggerConfig != null;
    boolean madeChanges = false;

    Level _level = null;
    if (!(level == null || "unset".equals(level) || "null".equals(level))) {
      try {
        _level = Level.valueOf(level);
      } catch (IllegalArgumentException iae) {
        log.error("{} is not a valid log level! Valid values are: {}", level, getAllLevels());
      }
    }
    if (loggerName.equals(loggerConfig.getName()) || isRootLogger(loggerName)) {
      loggerConfig.setLevel(_level);
      madeChanges = true;
    } else {
      // It doesn't have its own logger config yet so let's create one
      LoggerConfig explicitConfig = new LoggerConfig(loggerName, _level, true);
      explicitConfig.setParent(loggerConfig);
      config.addLogger(loggerName, explicitConfig);
      madeChanges = true;
    }

    if (madeChanges) {
      ctx.updateLoggers();
      if (log.isInfoEnabled()) {
        log.info("Setting log level to '{}' for logger: {}", _level, loggerName);
      }
    }
  }

  protected boolean isRootLogger(String loggerName) {
    return LoggerInfo.ROOT_NAME.equals(loggerName);
  }

  protected LoggerConfig getLoggerConfig(LoggerContext ctx, String loggerName) {
    Configuration config = ctx.getConfiguration();
    return isRootLogger(loggerName)
        ? config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME)
        : config.getLoggerConfig(loggerName);
  }

  @Override
  public Collection<LoggerInfo> getAllLoggers() {
    Logger root = LogManager.getRootLogger();
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    LoggerConfig rootConfig = ctx.getConfiguration().getRootLogger();
    Map<String, LoggerInfo> map = new HashMap<>(ctx.getLoggers().size());

    // First add the ROOT loger with the ROOT level
    map.put(
        LoggerInfo.ROOT_NAME,
        new Log4j2Info(
            LoggerInfo.ROOT_NAME, root.getLevel(), null != rootConfig.getExplicitLevel()));

    // Next get the explicitly configured loggers
    Map<String, LoggerConfig> configs = ctx.getConfiguration().getLoggers();
    for (Map.Entry<String, LoggerConfig> config : configs.entrySet()) {
      final String name = config.getKey();
      final Logger logger = ctx.getLogger(name);

      if (logger == root || root.equals(logger) || isRootLogger(name) || "".equals(name)) {
        continue;
      }

      // NOTE: just because we have an explicit configuration, doesn't mean we have an explitly set
      // level
      // (Configuration might be for some other property, and level is still inherited)
      map.putIfAbsent(
          name,
          new Log4j2Info(name, logger.getLevel(), null != config.getValue().getExplicitLevel()));
    }

    // Now add any "in use" loggers (that aren't already explicitly configured) and their parents
    for (Logger logger : ctx.getLoggers()) {
      String name = logger.getName();

      if (logger == root || root.equals(logger) || isRootLogger(name) || "".equals(name)) {
        continue;
      }

      // If we didn't already see a LoggerConfig for these loggers, then their level is
      // not (explicitly) set
      map.putIfAbsent(name, new Log4j2Info(name, logger.getLevel(), false));
      while (true) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) break;

        name = name.substring(0, dot);
        map.putIfAbsent(name, new Log4j2Info(name, logger.getLevel(), false));
      }
    }

    return map.values();
  }

  @Override
  public void setThreshold(String level) {
    Log4j2Appender app = getAppender();
    Level current = app.getThreshold();
    app.setThreshold(Level.toLevel(level));
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    LoggerConfig config = getLoggerConfig(ctx, LoggerInfo.ROOT_NAME);
    config.removeAppender(app.getName());
    config.addAppender(app, app.getThreshold(), app.getFilter());
    ((LoggerContext) LogManager.getContext(false)).updateLoggers();
    if (log.isInfoEnabled()) {
      log.info("Updated watcher threshold from {} to {} ", current, level);
    }
  }

  @Override
  public String getThreshold() {
    return String.valueOf(getAppender().getThreshold());
  }

  protected Log4j2Appender getAppender() {
    if (appender == null)
      throw new IllegalStateException(
          "No appenders configured! Must call registerListener(ListenerConfig) first.");
    return appender;
  }

  @Override
  public void registerListener(ListenerConfig cfg) {
    if (history != null) throw new IllegalStateException("History already registered");

    history = new CircularList<LogEvent>(cfg.size);

    Level threshold = (cfg.threshold != null) ? Level.toLevel(cfg.threshold) : Level.WARN;
    ThresholdFilter filter =
        ThresholdFilter.createFilter(threshold, Filter.Result.ACCEPT, Filter.Result.DENY);

    // If there's already an appender like this, remove it
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    LoggerConfig config = getLoggerConfig(ctx, LoggerInfo.ROOT_NAME);

    appender = new Log4j2Appender(this, filter, threshold); // "Log4j2WatcherAppender"

    config.removeAppender(appender.getName());

    if (!appender.isStarted()) appender.start();

    config.addAppender(appender, threshold, filter);
    ctx.updateLoggers();
  }

  @Override
  public long getTimestamp(LogEvent event) {
    return event.getTimeMillis();
  }

  @Override
  public SolrDocument toSolrDocument(LogEvent event) {
    SolrDocument doc = new SolrDocument();
    doc.setField("time", new Date(event.getTimeMillis()));
    doc.setField("level", event.getLevel().toString());
    doc.setField("logger", event.getLoggerName());
    Message message = event.getMessage();
    doc.setField("message", message.getFormattedMessage());
    Throwable t = message.getThrowable();
    if (t != null) {
      StringWriter trace = new StringWriter();
      t.printStackTrace(new PrintWriter(trace));
      doc.setField("trace", trace.toString());
    }

    Map<String, String> contextMap = event.getContextMap();
    if (contextMap != null) {
      for (Map.Entry<String, String> entry : contextMap.entrySet())
        doc.setField(entry.getKey(), entry.getValue());
    }

    if (!doc.containsKey("core"))
      doc.setField("core", ""); // avoids an ugly "undefined" column in the UI

    return doc;
  }
}
