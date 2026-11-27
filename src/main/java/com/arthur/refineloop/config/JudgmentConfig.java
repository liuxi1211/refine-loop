package com.arthur.refineloop.config;

import com.arthur.refineloop.data.mapper.JudgmentMapper;
import com.arthur.refineloop.data.mapper.JudgmentUnitRefMapper;
import com.arthur.refineloop.data.mapper.SwIndustryMemberMapper;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;
import com.arthur.refineloop.data.mapper.UnitFactMapper;
import com.arthur.refineloop.judgment.BenchmarkResolver;
import com.arthur.refineloop.judgment.JudgmentArchiveContextResolver;
import com.arthur.refineloop.judgment.JudgmentArchiveService;
import com.arthur.refineloop.judgment.JudgmentExpiryCalculator;
import com.arthur.refineloop.judgment.JudgmentIdGenerator;
import com.arthur.refineloop.judgment.JudgmentPayloadAssembler;
import com.arthur.refineloop.judgment.JudgmentReferenceChecker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * 判断域装配：存档器 + 编号生成 + payload 组装 + 基准解析。
 *
 * <p>与 {@code CapabilityConfig} 同构：领域类保持<b>纯类</b>（可直接 new 出来单测），
 * Spring 装配只在这一处显式声明——因此「存档器依赖了哪些 Mapper、哪个事务模板」是可审阅的。
 *
 * <p>{@link TransactionTemplate} 由 Spring Boot 在存在单一 {@code PlatformTransactionManager} 时自动装配
 * （SQLite 走 {@code DataSourceTransactionManager}）；这里直接注入而不自建，是为了让事务边界与
 * 数据源、隔离级别只有一份事实源。
 */
@Configuration
public class JudgmentConfig {

    @Bean
    public JudgmentIdGenerator judgmentIdGenerator() {
        return new JudgmentIdGenerator();
    }

    @Bean
    public JudgmentPayloadAssembler judgmentPayloadAssembler(TushareLookupMapper lookupMapper) {
        return new JudgmentPayloadAssembler(new JudgmentExpiryCalculator(lookupMapper));
    }

    @Bean
    public BenchmarkResolver benchmarkResolver(SwIndustryMemberMapper industryMemberMapper,
                                               JudgmentProperties judgmentProperties) {
        return new BenchmarkResolver(industryMemberMapper, judgmentProperties);
    }

    /** M1 的归因引用检查：恒为「未被引用」；M3 落地归因表后替换这个 Bean，存档器不改。 */
    @Bean
    public JudgmentReferenceChecker judgmentReferenceChecker() {
        return JudgmentReferenceChecker.none();
    }

    @Bean
    public JudgmentArchiveService judgmentArchiveService(JudgmentMapper judgmentMapper,
                                                         JudgmentUnitRefMapper unitRefMapper,
                                                         TushareLookupMapper lookupMapper,
                                                          UnitFactMapper unitFactMapper,
                                                         JudgmentIdGenerator judgmentIdGenerator,
                                                         JudgmentPayloadAssembler assembler,
                                                         BenchmarkResolver benchmarkResolver,
                                                         JudgmentReferenceChecker referenceChecker,
                                                         TransactionTemplate transactionTemplate,
                                                         Clock clock) {
        return new JudgmentArchiveService(judgmentMapper, unitRefMapper,
                new JudgmentArchiveContextResolver(lookupMapper, unitFactMapper),
                judgmentIdGenerator, assembler, benchmarkResolver, referenceChecker,
                transactionTemplate, clock);
    }
}
