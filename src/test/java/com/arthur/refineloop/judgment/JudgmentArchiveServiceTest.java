package com.arthur.refineloop.judgment;

import com.arthur.refineloop.capability.support.ValuationFixtures;
import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.util.JsonUtils;
import com.arthur.refineloop.config.JudgmentProperties;
import com.arthur.refineloop.data.entity.JudgmentEntity;
import com.arthur.refineloop.data.entity.JudgmentUnitRefEntity;
import com.arthur.refineloop.data.mapper.JudgmentMapper;
import com.arthur.refineloop.data.mapper.JudgmentUnitRefMapper;
import com.arthur.refineloop.data.mapper.SwIndustryMemberMapper;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;
import com.arthur.refineloop.judgment.model.JudgmentArchiveRequest;
import com.arthur.refineloop.judgment.model.JudgmentArchiveResult;
import com.arthur.refineloop.judgment.support.JudgmentFixtures;
import com.arthur.refineloop.support.SchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T10 验收：判断存档的冻结语义、重跑取代、归因引用拒绝、降级单路径消费。
 *
 * <p>全部跑在真实 SQLite 上（schema 由 {@code db/schema.sql} 建成），因为本任务的风险几乎都在
 * 数据库约束上：{@code uk_judgment_active} 生成列、唯一索引冲突、事务回滚后的旧行状态——
 * 用内存 Mock 只能测到「代码想做什么」，测不到「数据库允许什么」。
 */
@DisplayName("JudgmentArchiveService：判断存档与冻结语义")
class JudgmentArchiveServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-26T11:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private JdbcTemplate jdbc;
    private JudgmentMapper judgmentMapper;
    private JudgmentUnitRefMapper unitRefMapper;
    private JudgmentIdGenerator idGenerator;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        judgmentMapper = mapperOf(jdbc, JudgmentMapper.class);
        unitRefMapper = mapperOf(jdbc, JudgmentUnitRefMapper.class);
        idGenerator = new JudgmentIdGenerator();
        ValuationFixtures.stock(jdbc, JudgmentFixtures.TS_CODE, "平安银行");
        ValuationFixtures.industryMember(jdbc, JudgmentFixtures.TS_CODE, "801780.SI", "20200101", null);
    }

    @Test
    @DisplayName("正常判断：落库一行 + 每单元一条引用，冗余列与 payload 同源")
    void archivesNormalJudgment() {
        JudgmentArchiveResult result = service().archive(JudgmentFixtures.request(7L));

        assertThat(result.judgmentId()).isEqualTo("J-20261126-P1-0001");
        assertThat(result.supersededJudgmentId()).isNull();
        assertThat(result.reaffirm()).isFalse();

        JudgmentEntity stored = judgmentMapper.selectByJudgmentId(result.judgmentId());
        assertThat(stored).isNotNull();
        assertThat(stored.getTradeDate()).isEqualTo(JudgmentFixtures.TRADE_DATE);
        assertThat(stored.getTsCode()).isEqualTo(JudgmentFixtures.TS_CODE);
        assertThat(stored.getPipelineId()).isEqualTo("P1");
        assertThat(stored.getReportId()).isEqualTo(7L);
        assertThat(stored.getMethodVersion()).isEqualTo(JudgmentFixtures.METHOD_VERSION);
        assertThat(stored.getDirection()).isEqualTo("bullish");
        assertThat(stored.getHorizonDays()).isEqualTo(60);
        assertThat(stored.getExpireAt()).isEqualTo("2027-01-25");
        assertThat(stored.getConfidence()).isEqualTo("中");
        assertThat(stored.getFrozen()).isEqualTo(1);
        assertThat(stored.getSuperseded()).isZero();
        assertThat(stored.getInvalidatedAt()).isNull();
        assertThat(stored.getCreatedAt()).isNotNull();

        Map<String, Object> payload = JsonUtils.parseMap(stored.getPayload());
        assertThat(payload).containsEntry("judgment_id", result.judgmentId())
                .containsEntry("stock_name", "平安银行")
                .containsEntry("degraded", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> benchmark = (Map<String, Object>) payload.get("benchmark");
        assertThat(benchmark).containsEntry("industry_code", "801780.SI")
                .containsEntry("index_code", "000300.SH");

        List<JudgmentUnitRefEntity> refs = unitRefMapper.selectByJudgmentId(result.judgmentId());
        assertThat(refs).hasSize(2);
        assertThat(refs).extracting(JudgmentUnitRefEntity::getUnitId)
                .containsExactlyInAnyOrder(JudgmentFixtures.COMPUTE_UNIT, JudgmentFixtures.INTERPRET_UNIT);
        assertThat(refs).allSatisfy(ref -> {
            assertThat(ref.getUnitVersion()).isEqualTo("v1.0");
            assertThat(ref.getOutputSummary()).isNotBlank();
        });
    }

    @Test
    @DisplayName("重跑同组：旧行 superseded=1 + invalidated_at，新行 superseded=0，uk_judgment_active 不冲突")
    void rerunSupersedesPreviousJudgment() {
        JudgmentArchiveResult first = service().archive(JudgmentFixtures.request(7L));
        JudgmentArchiveResult second = service().archive(JudgmentFixtures.request(7L));

        assertThat(first.judgmentId()).isEqualTo("J-20261126-P1-0001");
        assertThat(second.judgmentId()).isEqualTo("J-20261126-P1-0002");
        assertThat(second.supersededJudgmentId()).isEqualTo(first.judgmentId());
        // 同日重跑是「修正」，不是「重申」
        assertThat(second.reaffirm()).isFalse();

        JudgmentEntity old = judgmentMapper.selectByJudgmentId(first.judgmentId());
        assertThat(old.getSuperseded()).isEqualTo(1);
        assertThat(old.getInvalidatedAt()).isNotNull();
        // 冻结语义：旧行 payload 一个字节都没变（唯一变化的只有两个标记列）
        assertThat(old.getPayload()).isEqualTo(JsonUtils.toJson(first.payload()));

        JudgmentEntity active = judgmentMapper.selectActive(
                JudgmentFixtures.TS_CODE, JudgmentFixtures.TRADE_DATE, "P1");
        assertThat(active.getJudgmentId()).isEqualTo(second.judgmentId());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judgment", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judgment WHERE superseded = 0", Long.class))
                .isEqualTo(1L);
        // 引用行跟着新判断走，旧判断的引用保留（判断不可删除）
        assertThat(unitRefMapper.selectByJudgmentId(second.judgmentId())).hasSize(2);
        assertThat(unitRefMapper.selectByJudgmentId(first.judgmentId())).hasSize(2);
    }

    @Test
    @DisplayName("已被归因引用：拒绝重跑（6003），旧行保持有效且不新增行")
    void refusesRerunWhenReferenced() {
        JudgmentArchiveResult first = service().archive(JudgmentFixtures.request(7L));

        ApiException error = catchThrowableOfType(
                () -> service(ignored -> true).archive(JudgmentFixtures.request(7L)),
                ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(6003);
        assertThat(error.getMessage()).contains(first.judgmentId());
        JudgmentEntity stillActive = judgmentMapper.selectByJudgmentId(first.judgmentId());
        assertThat(stillActive.getSuperseded()).isZero();
        assertThat(stillActive.getInvalidatedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judgment", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("下一交易日同方向：is_reaffirm=true，且不与前一日的有效判断冲突")
    void marksReaffirmOnLaterTradingDay() {
        service().archive(JudgmentFixtures.request(7L));
        JudgmentArchiveResult second = service().archive(requestOn("20261127"));

        assertThat(second.reaffirm()).isTrue();
        assertThat(second.supersededJudgmentId()).as("不同交易日的判断是两份独立判断").isNull();
        assertThat(second.judgmentId()).isEqualTo("J-20261127-P1-0001");

        @SuppressWarnings("unchecked")
        Map<String, Object> direction = (Map<String, Object>) second.payload().get("direction");
        assertThat(direction).containsEntry("is_reaffirm", true);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judgment WHERE superseded = 0", Long.class))
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("降级判断与正常判断同一条路径：字段完整，data_gaps 记录降级原因")
    void archivesDegradedJudgmentThroughSamePath() {
        JudgmentArchiveResult result = service().archive(JudgmentFixtures.degradedRequest(9L));

        JudgmentEntity stored = judgmentMapper.selectByJudgmentId(result.judgmentId());
        assertThat(stored.getDirection()).isEqualTo("neutral");
        assertThat(stored.getHorizonDays()).isEqualTo(5);
        assertThat(stored.getExpireAt()).isEqualTo("2026-12-03");
        assertThat(stored.getConfidence()).isEqualTo("低");

        Map<String, Object> payload = JsonUtils.parseMap(stored.getPayload());
        assertThat(payload).containsEntry("degraded", true);
        assertThat(payload.get("data_gaps")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .anySatisfy(gap -> assertThat(String.valueOf(gap)).contains("L3 校验重试耗尽"));
        assertThat(payload.get("verification_rules")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
        assertThat(unitRefMapper.selectByJudgmentId(result.judgmentId())).hasSize(2);
    }

    @Test
    @DisplayName("行业归属缺失：benchmark.industry_code 留空 + data_gaps 留痕，判断照常存档")
    void recordsIndustryGapWhenMembershipMissing() {
        JdbcTemplate empty = SchemaTestSupport.newJdbcTemplate();
        ValuationFixtures.stock(empty, JudgmentFixtures.TS_CODE, "平安银行");

        JudgmentArchiveResult result = service(empty, JudgmentReferenceChecker.none(), idGenerator)
                .archive(JudgmentFixtures.request(7L));

        Map<String, Object> payload = JsonUtils.parseMap(
                mapperOf(empty, JudgmentMapper.class).selectByJudgmentId(result.judgmentId()).getPayload());
        @SuppressWarnings("unchecked")
        Map<String, Object> benchmark = (Map<String, Object>) payload.get("benchmark");
        assertThat(benchmark).containsEntry("industry_code", "");
        assertThat(payload.get("data_gaps")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .anySatisfy(gap -> assertThat(String.valueOf(gap)).contains("行业归属缺失"));
    }

    @Test
    @DisplayName("股票名称缺失：stock_name 回落为代码 + data_gaps 留痕")
    void fallsBackToCodeWhenStockNameMissing() {
        JdbcTemplate empty = SchemaTestSupport.newJdbcTemplate();
        ValuationFixtures.industryMember(empty, JudgmentFixtures.TS_CODE, "801780.SI", "20200101", null);

        JudgmentArchiveResult result = service(empty, JudgmentReferenceChecker.none(), idGenerator)
                .archive(JudgmentFixtures.request(7L));

        Map<String, Object> payload = JsonUtils.parseMap(
                mapperOf(empty, JudgmentMapper.class).selectByJudgmentId(result.judgmentId()).getPayload());
        assertThat(payload).containsEntry("stock_name", JudgmentFixtures.TS_CODE);
        assertThat(payload.get("data_gaps")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .anySatisfy(gap -> assertThat(String.valueOf(gap)).contains("股票名称缺失"));
    }

    @Test
    @DisplayName("编号并发冲突：唯一索引报冲突后重试，最终序号 +1 且只留一行有效判断")
    void retriesAfterDuplicateJudgmentId() {
        service().archive(JudgmentFixtures.request(7L));

        JudgmentIdGenerator conflicting = new JudgmentIdGenerator() {
            private int calls;

            @Override
            public String next(String tradeDate, String pipelineId, String latestJudgmentId) {
                calls++;
                return calls == 1 ? "J-20261126-P1-0001" : super.next(tradeDate, pipelineId, latestJudgmentId);
            }
        };

        JudgmentArchiveResult result = service(jdbc, JudgmentReferenceChecker.none(), conflicting)
                .archive(JudgmentFixtures.request(7L));

        assertThat(result.judgmentId()).isEqualTo("J-20261126-P1-0002");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judgment WHERE superseded = 0", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("载荷不合法：写库前失败（6001），库内一行都不留")
    void writesNothingWhenPayloadInvalid() {
        Map<String, Object> block = JudgmentFixtures.copyOf(JudgmentFixtures.block());
        block.put("verification_rules", List.of());

        ApiException error = catchThrowableOfType(() -> service().archive(
                JudgmentFixtures.request(JudgmentFixtures.TRADE_DATE, JudgmentFixtures.TS_CODE, block,
                        false, List.of(), JudgmentFixtures.method(), 7L)), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(6001);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judgment", Long.class)).isZero();
    }

    // ================================================================ 夹具

    private JudgmentArchiveService service() {
        return service(jdbc, JudgmentReferenceChecker.none(), idGenerator);
    }

    private JudgmentArchiveService service(JudgmentReferenceChecker checker) {
        return service(jdbc, checker, idGenerator);
    }

    private JudgmentArchiveService service(JdbcTemplate target, JudgmentReferenceChecker checker,
                                           JudgmentIdGenerator generator) {
        return new JudgmentArchiveService(
                mapperOf(target, JudgmentMapper.class),
                mapperOf(target, JudgmentUnitRefMapper.class),
                mapperOf(target, TushareLookupMapper.class),
                generator,
                new JudgmentPayloadAssembler(),
                new BenchmarkResolver(mapperOf(target, SwIndustryMemberMapper.class),
                        new JudgmentProperties()),
                checker,
                new TransactionTemplate(new DataSourceTransactionManager(target.getDataSource())),
                CLOCK);
    }

    private static <T> T mapperOf(JdbcTemplate target, Class<T> type) {
        return SchemaTestSupport.mapper(target, type);
    }

    /** 同一份判断内容，换成另一交易日的请求（用于重申判定）。 */
    private static JudgmentArchiveRequest requestOn(String tradeDate) {
        Map<String, Object> block = JudgmentFixtures.copyOf(JudgmentFixtures.block());
        block.put("trade_date", tradeDate);
        return JudgmentFixtures.request(tradeDate, JudgmentFixtures.TS_CODE, block, false, List.of(),
                JudgmentFixtures.method(), 8L);
    }
}
