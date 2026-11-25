package com.arthur.refineloop.common.util;

import com.arthur.refineloop.common.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 工具类契约测试：这些方法被业务代码高频调用，行为必须钉死。 */
class CommonUtilsTest {

    /* ---------------- Strings ---------------- */

    @Test
    void strings_判空与归一() {
        assertThat(Strings.isBlank("  \t")).isTrue();
        assertThat(Strings.isBlank(null)).isTrue();
        assertThat(Strings.isNotBlank("a")).isTrue();
        assertThat(Strings.trimToNull("  ")).isNull();
        assertThat(Strings.trimToNull(" x ")).isEqualTo("x");
        assertThat(Strings.defaultIfBlank("", "d")).isEqualTo("d");
        assertThat(Strings.equalsIgnoreCase(null, null)).isTrue();
        assertThat(Strings.containsIgnoreCase("SzzzZ", "zz")).isTrue();
    }

    @Test
    void strings_驼峰下划线互转对齐Tushare口径() {
        assertThat(Strings.camelToUnderline("tsCode")).isEqualTo("ts_code");
        assertThat(Strings.camelToUnderline("tradeDate")).isEqualTo("trade_date");
        assertThat(Strings.camelToUnderline("already_snake")).isEqualTo("already_snake");
        assertThat(Strings.underlineToCamel("ts_code")).isEqualTo("tsCode");
        assertThat(Strings.underlineToCamel("trade_date")).isEqualTo("tradeDate");
        assertThat(Strings.upperFirst("abc")).isEqualTo("Abc");
        assertThat(Strings.lowerFirst("ABC")).isEqualTo("aBC");
    }

    @Test
    void strings_截断与切分() {
        assertThat(Strings.truncate("abcdef", 4)).isEqualTo("abc…");
        assertThat(Strings.truncate("abc", 10)).isEqualTo("abc");
        assertThat(Strings.truncate(null, 3)).isNull();
        assertThat(Strings.splitComma(" a, ,b ,")).containsExactly("a", "b");
        assertThat(Strings.joinComma(List.of("a", 1, " "))).isEqualTo("a,1");
        assertThat(Strings.leftPad("7", 3, '0')).isEqualTo("007");
    }

    /* ---------------- Asserts ---------------- */

    @Test
    void asserts_失败是400而不是NPE() {
        assertThatThrownBy(() -> Asserts.notNull(null, "table"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("table");
                });
        assertThatThrownBy(() -> Asserts.notBlank("  ", "code")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Asserts.inRange(0, 1, 10, "page")).isInstanceOf(ApiException.class);
        assertThat(Asserts.notNull("v", "x")).isEqualTo("v");
    }

    /* ---------------- DateTimes ---------------- */

    @Test
    void dateTimes_业务日期与系统时间两条口径() {
        LocalDate date = DateTimes.parseCompactDate("20261124");
        assertThat(date).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(DateTimes.formatCompact(date)).isEqualTo("20261124");
        assertThat(DateTimes.formatCompact(null)).isNull();
        assertThat(DateTimes.formatCompact(null, "—")).isEqualTo("—");
        assertThatThrownBy(() -> DateTimes.parseCompactDate("2026-11-24")).isInstanceOf(ApiException.class);
        assertThat(DateTimes.tryParseCompactDate("bad")).isEmpty();

        LocalDateTime dateTime = LocalDateTime.of(2026, 11, 24, 15, 30, 5);
        assertThat(DateTimes.formatDateTime(dateTime)).isEqualTo("2026-11-24 15:30:05");
        assertThat(DateTimes.tryParseDateTime("2026-11-24T15:30:05")).contains(dateTime);
        assertThat(DateTimes.tryParseDateTime("2026-11-24")).contains(LocalDate.of(2026, 11, 24).atStartOfDay());

        assertThat(DateTimes.endOfDay(date)).isEqualTo(LocalDateTime.of(2026, 11, 24, 23, 59, 59));
        assertThat(DateTimes.daysBetween(date, date.plusDays(3))).isEqualTo(3);
        assertThat(DateTimes.isWeekend(LocalDate.of(2026, 11, 21))).isTrue();
        assertThat(DateTimes.between(date, date.minusDays(1), null)).isTrue();
    }

    @Test
    void dateTimes_时钟注入让时间可控() {
        Clock fixed = Clock.fixed(Instant.parse("2026-11-24T07:30:00Z"), ZoneId.of("Asia/Shanghai"));
        assertThat(DateTimes.today(fixed)).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(DateTimes.now(fixed)).isEqualTo(LocalDateTime.of(2026, 11, 24, 15, 30));
        assertThat(DateTimes.humanize(Duration.ofMinutes(22).plusSeconds(48))).isEqualTo("22m48s");
        assertThat(DateTimes.humanize(850)).isEqualTo("850ms");
        assertThat(DateTimes.parseDuration("PT30S")).contains(Duration.ofSeconds(30));
        assertThat(DateTimes.parseDuration("120")).contains(Duration.ofSeconds(120));
    }

    /* ---------------- Decimals / Numbers ---------------- */

    @Test
    void decimals_缺失与除零返回null而不是0() {
        assertThat(Decimals.add(null, BigDecimal.ONE)).isEqualByComparingTo("1");
        assertThat(Decimals.divideOrNull(BigDecimal.ONE, BigDecimal.ZERO)).isNull();
        assertThat(Decimals.divideOrNull(BigDecimal.ONE, null)).isNull();
        assertThat(Decimals.divideOrNull(BigDecimal.ONE, BigDecimal.valueOf(3))).isEqualByComparingTo("0.3333");
        assertThat(Decimals.percent(BigDecimal.ONE, BigDecimal.valueOf(4), 2)).isEqualByComparingTo("25.00");
        assertThat(Decimals.percent(BigDecimal.ONE, BigDecimal.ZERO, 2)).isNull();
        assertThat(Decimals.scale(BigDecimal.valueOf(1.23456), 2)).isEqualByComparingTo("1.23");
        assertThat(Decimals.average(java.util.Arrays.asList(BigDecimal.ONE, null, BigDecimal.valueOf(3))))
                .isEqualByComparingTo("2");
        assertThat(Decimals.sum(List.of())).isEqualByComparingTo("0");
        assertThat(Decimals.gt(null, BigDecimal.ZERO)).isFalse();
        assertThat(Decimals.toPlainString(new BigDecimal("1E+2"), "-")).isEqualTo("100");
        assertThat(Decimals.toPlainString(null, "-")).isEqualTo("-");
    }

    @Test
    void numbers_容忍外部接口的缺失表现() {
        assertThat(Numbers.tryParseInt(" 12 ")).hasValue(12);
        assertThat(Numbers.tryParseInt("12.0")).hasValue(12);
        assertThat(Numbers.tryParseInt("12.5")).isEmpty();
        assertThat(Numbers.parseInt("--", 7)).isEqualTo(7);
        assertThat(Numbers.tryParseDecimal("NaN")).isEmpty();
        assertThat(Numbers.tryParseBoolean("Y")).contains(true);
        assertThat(Numbers.tryParseBoolean("否")).contains(false);
        assertThat(Numbers.isMissing("--")).isTrue();
        assertThat(Numbers.clamp(120, 1, 100)).isEqualTo(100);
    }

    /* ---------------- Ids / Mask ---------------- */

    @Test
    void ids_格式稳定且可排序() throws InterruptedException {
        assertThat(Ids.uuid32()).hasSize(32).doesNotContain("-");
        assertThat(Ids.timestampedId("R")).matches("R\\d{14}-[a-z0-9]{4}");
        String ulid1 = Ids.ulid();
        Thread.sleep(2);
        String ulid2 = Ids.ulid();
        assertThat(ulid1).hasSize(26);
        assertThat(ulid1.compareTo(ulid2)).isNegative();
        assertThat(Ids.randomBase36(8)).hasSize(8);
    }

    @Test
    void mask_敏感信息脱敏() {
        assertThat(MaskUtils.maskSecret("sk-1234567890")).isEqualTo("******");
        assertThat(MaskUtils.maskToken("abcdefghijklmn")).isEqualTo("abcd****mn");
        assertThat(MaskUtils.maskToken("short")).isEqualTo("******");
        assertThat(MaskUtils.maskPhone("13812341234")).isEqualTo("138****1234");
        assertThat(MaskUtils.maskEmail("abcdef@example.com")).isEqualTo("a***f@example.com");
        assertThat(MaskUtils.maskIdentifier("110101199001011234")).startsWith("1101").endsWith("1234");
    }

    /* ---------------- Json ---------------- */

    @Test
    void json_序列化反序列化与容错() {
        Payload payload = new Payload("a", 1);
        String json = JsonUtils.toJson(payload);
        assertThat(JsonUtils.parse(json, Payload.class)).isEqualTo(payload);
        assertThat(JsonUtils.parseList("[{\"code\":\"a\",\"value\":1}]", Payload.class)).hasSize(1);
        assertThat(JsonUtils.parseMap("{\"a\":1}")).containsEntry("a", 1);
        assertThat(JsonUtils.tryParse("{bad", Payload.class)).isEmpty();
        assertThat(JsonUtils.isValid("{\"a\":1}")).isTrue();
        assertThat(JsonUtils.isValid("")).isFalse();
        assertThatThrownBy(() -> JsonUtils.parse("{bad", Payload.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON 解析失败");
        assertThat(JsonUtils.parseList("", Payload.class)).isEmpty();
        assertThat(JsonUtils.parseMap("")).isEmpty();
    }

    /* ---------------- Trees / Csvs ---------------- */

    @Test
    void trees_构建树并保留孤儿节点为根() {
        Node root = new Node(1, null);
        Node child = new Node(2, 1);
        Node grandChild = new Node(3, 2);
        Node orphan = new Node(4, 99);

        List<Node> roots = Trees.build(List.of(root, child, grandChild, orphan),
                Node::id, Node::parentId, Node::setChildren);

        assertThat(roots).containsExactly(root, orphan);
        assertThat(root.children()).containsExactly(child);
        assertThat(child.children()).containsExactly(grandChild);
        assertThat(Trees.flatten(roots, Node::children)).hasSize(4);
    }

    @Test
    void csv_按RFC4180转义() {
        String csv = Csvs.toCsv(List.of("a", "b"), List.of(
                List.of("x,y", "he said \"hi\""),
                java.util.Arrays.asList(null, "line1\nline2")));
        assertThat(csv).isEqualTo("a,b\r\n\"x,y\",\"he said \"\"hi\"\"\"\r\n,\"line1\nline2\"\r\n");
        assertThat(Csvs.escape("plain")).isEqualTo("plain");
    }

    /* ---------------- Retry / StopWatch / ThreadPools ---------------- */

    @Test
    void retry_退避重试与不可重试异常直接抛出() {
        int[] attempts = {0};
        String result = RetryUtils.retry("test", 3, Duration.ofMillis(1), () -> {
            if (++attempts[0] < 3) {
                throw new IllegalStateException("transient");
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(attempts[0]).isEqualTo(3);

        int[] hard = {0};
        assertThatThrownBy(() -> RetryUtils.retry("test", 5, Duration.ofMillis(1), 2.0,
                e -> !(e instanceof IllegalArgumentException), () -> {
                    hard[0]++;
                    throw new IllegalArgumentException("bad param");
                }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(hard[0]).isEqualTo(1);
    }

    @Test
    void stopWatch_分段计时() throws InterruptedException {
        StopWatch watch = StopWatch.start("unit");
        Thread.sleep(2);
        watch.lap("first");
        assertThat(watch.elapsedMillis()).isGreaterThanOrEqualTo(0);
        assertThat(watch.laps()).hasSize(1);
        assertThat(watch.laps().get(0).label()).isEqualTo("first");
        assertThat(watch.elapsedText()).isNotBlank();
    }

    @Test
    void threadPools_命名与优雅关闭() throws Exception {
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor(ThreadPools.platformThreadFactory("test"))) {
            assertThat(executor.submit(() -> Thread.currentThread().getName()).get()).startsWith("test-");
        }
        var virtual = ThreadPools.virtualThreads("vt");
        assertThat(virtual.submit(() -> Thread.currentThread().isVirtual()).get()).isTrue();
        ThreadPools.shutdown(virtual, Duration.ofSeconds(1));
        assertThat(virtual.isShutdown()).isTrue();
    }

    /** JSON 与树结构测试用类型。 */
    public record Payload(String code, int value) {
    }

    /** 树结构测试用节点。 */
    public static final class Node {

        private final Integer id;
        private final Integer parentId;
        private List<Node> children = new ArrayList<>();

        Node(Integer id, Integer parentId) {
            this.id = id;
            this.parentId = parentId;
        }

        public Integer id() {
            return id;
        }

        public Integer parentId() {
            return parentId;
        }

        public List<Node> children() {
            return children;
        }

        public void setChildren(List<Node> children) {
            this.children = children;
        }
    }
}
