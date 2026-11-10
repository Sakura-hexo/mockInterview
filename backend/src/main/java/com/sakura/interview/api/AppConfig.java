package com.sakura.interview.api;

import com.sakura.interview.agent.AgentRuntime;
import com.sakura.interview.agent.SessionAnalyst;
import com.sakura.interview.agent.Supervisor;
import com.sakura.interview.rag.MilvusBankImporter;
import com.sakura.interview.infra.AgentLlmCaller;
import com.sakura.interview.infra.AgentScopeRuntime;
import com.sakura.interview.infra.ChatModels;
import com.sakura.interview.infra.SherpaTtsSpeaker;
import com.sakura.interview.infra.EmbeddingModels;
import com.sakura.interview.infra.EnvKeys;
import com.sakura.interview.infra.MySqlAnalysisCache;
import com.sakura.interview.infra.MySqlDb;
import com.sakura.interview.infra.MySqlReportStore;
import com.sakura.interview.infra.MySqlStatsStore;
import com.sakura.interview.infra.MySqlUserStore;
import com.sakura.interview.infra.PersistentSessionStore;
import com.sakura.interview.infra.RedisAuthStore;
import com.sakura.interview.infra.RedisSessionCache;
import com.sakura.interview.infra.SherpaAsrTranscriber;
import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.rag.MilvusQuestionStore;
import com.sakura.interview.session.AnalysisCache;
import com.sakura.interview.session.ContextCompressor;
import com.sakura.interview.report.ReportGenerator;
import com.sakura.interview.report.ReportService;
import com.sakura.interview.report.ReportWorker;
import com.sakura.interview.session.SessionStore;
import com.sakura.interview.tool.QuestionBankTool;
import com.zaxxer.hikari.HikariDataSource;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    public MySqlStatsStore statsStore(DataSource dataSource) {
        return new MySqlStatsStore(dataSource);
    }

    /** 管理员门禁：env ADMIN_USERNAME 指定谁可见管理页签，不配置则管理接口 404 零暴露。 */
    @Bean
    public AdminGate adminGate() {
        return StatsController.gateFromEnv();
    }

    @Bean
    public MilvusBankImporter bankImporter(MilvusQuestionStore questionStore) {
        return new MilvusBankImporter(questionStore);
    }

    @Bean
    public MySqlReportStore reportStore(DataSource dataSource) {
        return new MySqlReportStore(dataSource);
    }

    @Bean(destroyMethod = "close")
    public MilvusQuestionStore questionStore() {
        return new MilvusQuestionStore(EmbeddingModels.embedding());
    }

    /** 档位装配：主力档供面试官与报告，快档供摘要路由简历 JD 解析。 */
    @Bean
    public AgentLlmCaller mainLlmCaller() {
        return new AgentLlmCaller();
    }

    @Bean
    public AgentLlmCaller fastLlmCaller() {
        return new AgentLlmCaller(ChatModels.TIER_FAST);
    }

    @Bean
    public StructuredOutputInvoker mainInvoker(AgentLlmCaller mainLlmCaller) {
        return new StructuredOutputInvoker(mainLlmCaller);
    }

    @Bean
    public StructuredOutputInvoker fastInvoker(AgentLlmCaller fastLlmCaller) {
        return new StructuredOutputInvoker(fastLlmCaller);
    }

    @Bean
    public AgentScopeRuntime agentRuntime() {
        return new AgentScopeRuntime();
    }

    @Bean
    public QuestionBankTool questionBankTool(MilvusQuestionStore questionStore) {
        return new QuestionBankTool(questionStore);
    }

    @Bean
    public ContextCompressor contextCompressor(AgentLlmCaller fastLlmCaller) {
        return new ContextCompressor(fastLlmCaller);
    }

    @Bean
    public ReportService reportService(SessionStore sessionStore, MySqlReportStore reportStore) {
        return new ReportService(sessionStore, reportStore);
    }

    /** Worker 两写同事务用：数据源已就绪，Boot 自动装配 DataSourceTransactionManager。 */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    /** 业务指标接线：模型调用计时与熔断器状态挂到统一注册表。 */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsBinding() {
        return registry -> {
            ChatModels.bindMeterRegistry(registry);
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(ChatModels.circuitBreakerRegistry())
                    .bindTo(registry);
        };
    }

    /** 每用户每分钟 10 轮对话的限流注册表。 */
    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        return RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(10)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .build());
    }

    @Bean
    public ReportGenerator reportGenerator(StructuredOutputInvoker mainInvoker,
                                            SessionStore sessionStore) {
        return new ReportGenerator(mainInvoker, sessionStore);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public ReportWorker reportWorker(MySqlReportStore reportStore, ReportGenerator generator,
                                     SessionStore sessionStore, TransactionTemplate txTemplate,
                                     MeterRegistry meterRegistry) {
        return new ReportWorker(reportStore, generator, sessionStore, txTemplate, meterRegistry);
    }

    /** 压缩后台单线程：天然串行避免同会话摘要互相覆盖，守护线程不阻塞退出。 */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService compressionExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ctx-compress");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    public Supervisor supervisor(AgentRuntime runtime, StructuredOutputInvoker fastInvoker,
                                 SessionAnalyst sessionAnalyst, SessionStore sessionStore,
                                 ContextCompressor compressor,
                                 ReportService reportService, QuestionBankTool questionBank,
                                 ExecutorService compressionExecutor) {
        return new Supervisor(runtime, fastInvoker, sessionAnalyst, sessionStore, compressor,
                reportService, questionBank, compressionExecutor);
    }

    /** 建会话分析缓存：MySQL 事实源，重启后同简历同 JD 仍命中。 */
    @Bean
    public AnalysisCache analysisCache(DataSource dataSource) {
        return new MySqlAnalysisCache(dataSource);
    }

    @Bean
    public SessionAnalyst sessionAnalyst(StructuredOutputInvoker fastInvoker,
                                         AnalysisCache analysisCache) {
        return new SessionAnalyst(fastInvoker, analysisCache);
    }

    @Bean
    public AuthService authService(MySqlUserStore users, RedisAuthStore sessions) {
        AuthService service = new AuthService(users, sessions);
        seedBuiltInAdmin(service);
        return service;
    }

    /** TTS 合成通道：TTS_PROVIDER=local 走边车容器（本地 matcha 神经朗读，约 1 秒出声），
     *  off 时为关闭态占位（接口 404，前端回落浏览器朗读）。 */
    @Bean
    public TtsSpeaker ttsSpeaker() {
        if ("local".equals(EnvKeys.ttsProvider())) {
            return new SherpaTtsSpeaker(EnvKeys.ttsUrl());
        }
        return new DisabledTtsSpeaker();
    }

    /** ASR 识别通道：ASR_PROVIDER=sherpa 走边车容器（本地 SenseVoice 识别），
     *  off 时为关闭态占位（接口 404，前端回落浏览器识别）。 */
    @Bean
    public AsrTranscriber asrTranscriber() {
        if ("sherpa".equals(EnvKeys.asrProvider())) {
            return new SherpaAsrTranscriber(EnvKeys.asrUrl());
        }
        return new DisabledAsrTranscriber();
    }

    /** 内置管理员播种：仅当 ADMIN_USERNAME 指向内置名 admin 时生效，幂等（缺则建、密码不符则重置）。
     *  指向其他用户名时不播种不改密码——普通账号的管理权只由门禁判定，不动其凭据。 */
    private static void seedBuiltInAdmin(AuthService service) {
        if (!"admin".equals(EnvKeys.adminUsername())) {
            return;
        }
        service.ensureAccount("admin", EnvKeys.adminPassword());
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
                                           ExecutorService chatStreamExecutor, MeterRegistry meterRegistry,
                                           RateLimiterRegistry rateLimiters) {
        return new ChatStreamService(supervisor, sessionStore, chatStreamExecutor,
                meterRegistry, rateLimiters);
    }

    @Bean
    public AuthInterceptor authInterceptor(RedisAuthStore redisAuthStore) {
        return new AuthInterceptor(redisAuthStore);
    }
}
