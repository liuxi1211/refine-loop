package com.arthur.refineloop.tushare.client;

import com.arthur.refineloop.config.RateLimitProperties;
import com.arthur.refineloop.config.TushareProperties;
import com.arthur.refineloop.tushare.ratelimit.RateLimitCounterRecorder;
import com.arthur.refineloop.tushare.ratelimit.RateLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** T3 验收：列式响应按下标对齐、fields 透传、错误分型与重试。 */
class TushareClientTest {

    private static final String URL = "http://api.tushare.pro/";

    private TushareProperties props;
    private MockRestServiceServer server;
    private TushareClient client;

    @BeforeEach
    void setUp() {
        props = new TushareProperties();
        props.setBaseUrl("http://api.tushare.pro");
        props.setToken("test-token");
        props.setRetryCount(3);
        props.setRetryBackoffMillis(0L);

        RateLimitProperties limitProps = new RateLimitProperties();
        limitProps.setDefaultLimit(100_000);
        limitProps.setSmoothingEnabled(false);
        limitProps.setCounterEnabled(false);
        RateLimiterRegistry limiter = new RateLimiterRegistry(limitProps, Clock.systemDefaultZone(),
                millis -> {
                }, RateLimitCounterRecorder.NOOP);

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TushareClient(builder, props, limiter);
    }

    @Test
    void 按响应fields下标对齐且fields原样透传() {
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"api_name":"daily","token":"test-token","fields":"ts_code,trade_date,close"}
                        """, false))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"","data":{
                          "fields":["ts_code","trade_date","close"],
                          "items":[["000001.SZ","20240115",9.85],["600000.SH","20240115",7.21]]}}
                        """, MediaType.APPLICATION_JSON));

        List<Map<String, Object>> rows = client.call("daily",
                Map.of("trade_date", "20240115", "limit", "5000"), "ts_code,trade_date,close");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsOnlyKeys("ts_code", "trade_date", "close");
        assertThat(rows.get(0).get("ts_code")).isEqualTo("000001.SZ");
        assertThat(rows.get(0).get("trade_date")).isEqualTo("20240115");
        assertThat((BigDecimal) rows.get(0).get("close")).isEqualByComparingTo(new BigDecimal("9.85"));
        assertThat(rows.get(1).get("ts_code")).isEqualTo("600000.SH");
        server.verify();
    }

    @Test
    void 响应列是请求列子集时只包含响应实际列() {
        // 实测契约：整列全 NULL 会被省略、无效字段被静默丢弃 —— 解析不能假设请求列=响应列
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"","data":{
                          "fields":["ts_code","close"],
                          "items":[["000001.SZ",9.85]]}}
                        """, MediaType.APPLICATION_JSON));

        List<Map<String, Object>> rows = client.call("daily", Map.of(), "ts_code,trade_date,close,vol");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsOnlyKeys("ts_code", "close");
        server.verify();
    }

    @Test
    void 空结果返回空列表区分空数据() {
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"","data":null}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.call("express", Map.of("ts_code", "600519.SH"), "ts_code")).isEmpty();
    }

    @Test
    void 空items返回空列表() {
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"","data":{"fields":["ts_code"],"items":[]}}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.call("express", Map.of(), "ts_code")).isEmpty();
    }

    @Test
    void 参数错误50101不重试且抛专用异常() {
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess("""
                        {"code":50101,"msg":"必填参数 index_code"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.call("index_weight", Map.of("ts_code", "000300.SH"), "index_code"))
                .isInstanceOf(TushareParamException.class)
                .hasMessageContaining("50101");
        server.verify();   // 只发了一次请求，没有重试
    }

    @Test
    void 频次超限40203抛限流异常不重试() {
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess("""
                        {"code":40203,"msg":"请求过于频繁"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.call("report_rc", Map.of(), "ts_code"))
                .isInstanceOf(TushareRateLimitException.class);
        server.verify();
    }

    @Test
    void 网络5xx指数退避重试3次后抛网络异常() {
        server.expect(times(4), requestTo(URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.call("daily", Map.of(), "ts_code"))
                .isInstanceOf(TushareNetworkException.class);
        server.verify();
    }

    @Test
    void 重试后成功则返回数据() {
        server.expect(once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"","data":{"fields":["ts_code"],"items":[["000001.SZ"]]}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.call("daily", Map.of(), "ts_code")).hasSize(1);
        server.verify();
    }

    @Test
    void token未配置时抛出明确异常且不发请求() {
        props.setToken("");
        assertThatThrownBy(() -> client.call("daily", Map.of(), "ts_code"))
                .isInstanceOf(TushareException.class)
                .hasMessageContaining("TUSHARE_TOKEN");
        server.verify();
    }
}
