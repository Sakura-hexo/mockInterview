package com.sakura.interview.api;

import com.sakura.interview.agent.Supervisor;
import com.sakura.interview.infra.AgentLlmCaller;
import com.sakura.interview.infra.AgentScopeRuntime;
import com.sakura.interview.infra.EmbeddingModels;
import com.sakura.interview.infra.EnvKeys;
import com.sakura.interview.infra.MySqlDb;
import com.sakura.interview.infra.MySqlReportStore;
import com.sakura.interview.infra.MySqlUserStore;
import com.sakura.interview.infra.PersistentSessionStore;
import com.sakura.interview.infra.RedisAuthStore;
import com.sakura.interview.infra.RedisSessionCache;
import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.rag.MilvusQuestionStore;
import com.sakura.interview.session.ContextCompressor;
import com.sakura.interview.session.ReportGenerator;
import com.sakura.interview.session.ReportService;
import com.sakura.interview.session.ReportWorker;
import com.sakura.interview.session.SessionStore;
import com.sakura.interview.tool.QuestionBankAgentTool;
import com.sakura.interview.tool.QuestionBankTool;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 生产装配：把 CLI 时代的各组件挂成 Spring bean，与命令行入口共用同一批类。
 * 数据源唯一（自动配置退避，actuator 健康检查复用本池），建表与列迁移随启动幂等执行。
 */
@Configuration
public class AppConfig {

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource() {
        HikariDataSource ds = MySqlDb.dataSource();
        MySqlDb.init(ds);
        return ds;
    }

    @Bean(destroyMethod = "close")
    public RedisSessionCache redisSessionCache() {
        return new RedisSessionCache(EnvKeys.redisUri());
    }

    @Bean(destroyMethod = "close")
    public RedisAuthStore redisAuthStore() {
        return new RedisAuthStore(EnvKeys.redisUri());
    }

    /** 关闭由内嵌 RedisSessionCache 承担，本组合对象不重复销毁。 */
    @Bean(destroyMethod = "")
    public PersistentSessionStore sessionStore(DataSource dataSource, RedisSessionCache cache) {
        return new PersistentSessionStore(dataSource, cache);
    }

    @Bean
    public MySqlUserStore userStore(DataSource dataSource) {
        return new MySqlUserStore(dataSource);
    }

    @Bean
    public MySqlReportStore reportStore(DataSource dataSource) {
        return new MySqlReportStore(dataSource);
    }

    @Bean(destroyMethod = "close")
    public MilvusQuestionStore questionStore() {
        return new MilvusQuestionStore(EmbeddingModels.embedding());
    }

    @Bean
    public AgentLlmCaller agentLlmCaller() {
        return new AgentLlmCaller();
    }

    @Bean
    public StructuredOutputInvoker invoker(AgentLlmCaller caller) {
        return new StructuredOutputInvoker(caller);
    }

    @Bean
    public AgentScopeRuntime agentRuntime(MilvusQuestionStore questionStore) {
        return new AgentScopeRuntime(List.of(new QuestionBankAgentTool(
                new QuestionBankTool(questionStore))));
    }

    @Bean
    public ContextCompressor contextCompressor(AgentLlmCaller caller) {
        return new ContextCompressor(caller);
    }

    @Bean
    public ReportService reportService(SessionStore sessionStore, MySqlReportStore reportStore) {
        return new ReportService(sessionStore, reportStore);
    }

    @Bean
    public ReportGenerator reportGenerator(StructuredOutputInvoker invoker,
                                            SessionStore sessionStore) {
        return new ReportGenerator(invoker, sessionStore);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public ReportWorker reportWorker(MySqlReportStore reportStore, ReportGenerator generator,
                                     SessionStore sessionStore) {
        return new ReportWorker(reportStore, generator, sessionStore);
    }

    @Bean
    public Supervisor supervisor(AgentScopeRuntime runtime, StructuredOutputInvoker invoker,
                                 SessionStore sessionStore, ContextCompressor compressor,
                                 ReportService reportService) {
        return new Supervisor(runtime, invoker, sessionStore, compressor, reportService);
    }

    @Bean
    public AuthService authService(MySqlUserStore users, RedisAuthStore sessions) {
        return new AuthService(users, sessions);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService chatStreamExecutor() {
        return new ThreadPoolExecutor(4, 8, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                runnable -> {
                    Thread thread = new Thread(runnable, "chat-stream");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Bean
    public ChatStreamService chatStreamService(Supervisor supervisor, SessionStore sessionStore,
                                               ExecutorService chatStreamExecutor) {
        return new ChatStreamService(supervisor, sessionStore, chatStreamExecutor);
    }

    @Bean
    public AuthInterceptor authInterceptor(RedisAuthStore redisAuthStore) {
        return new AuthInterceptor(redisAuthStore);
    }
}
