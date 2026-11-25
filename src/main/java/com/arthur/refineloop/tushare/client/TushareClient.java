package com.arthur.refineloop.tushare.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.arthur.refineloop.config.TushareProperties;
import com.arthur.refineloop.tushare.ratelimit.RateLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全系统唯一 Tushare 数据出口（《Tushare接口对接手册》第 2 章契约）。
 *
 * <p>要点：
 * <ul>
 *   <li>POST {@code api.tushare.pro}，token 只在请求体顶层，来自环境变量（不落日志）；</li>
 *   <li>返回是列式结构，按<b>响应 data.fields 下标对齐</b>转 {@code List<Map>}；
 *       Tushare 可能省略整列为 NULL 的列、静默丢弃无效字段名，因此行 Map 只含响应实际列，
 *       缺失列由落库层按 NULL 处理（不能假设请求列 = 响应列）；</li>
 *   <li>错误分型：40203 频次超限、50101 参数错误 → 不重试直接抛；
 *       网络错误/5xx → 指数退避重试（1s/2s/4s，共 3 次）后抛 {@link TushareNetworkException}；</li>
 *   <li>每次 HTTP 尝试前先经 {@link RateLimiterRegistry} 取许可（分页每页都算一次调用）。</li>
 * </ul>
 */
public class TushareClient {

    private static final Logger log = LoggerFactory.getLogger(TushareClient.class);

    private final RestClient restClient;
    private final TushareProperties props;
    private final RateLimiterRegistry rateLimiter;

    public TushareClient(RestClient.Builder builder, TushareProperties props, RateLimiterRegistry rateLimiter) {
        this.restClient = builder.baseUrl(props.getBaseUrl()).build();
        this.props = props;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 调用一个 Tushare 接口。
     *
     * @param apiName 接口名（如 daily）
     * @param params  业务参数 + offset/limit（值建议字符串）；null 视为空参数
     * @param fields  请求字段串（附录 A 原文，下划线命名）；原样透传
     * @return 按响应列下标对齐的行 Map 列表；{@code code=0} 且 data/items 为空时返回空 List
     */
    public List<Map<String, Object>> call(String apiName, Map<String, Object> params, String fields) {
        String token = props.getToken();
        if (token == null || token.isBlank()) {
            throw new TushareException("TUSHARE_TOKEN 未配置：请通过环境变量注入 Tushare Pro token");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_name", apiName);
        body.put("token", token);
        body.put("params", params == null ? Map.of() : params);
        body.put("fields", fields == null ? "" : fields);

        String responseBody = executeWithRetry(apiName, body);
        return parse(apiName, responseBody);
    }

    private String executeWithRetry(String apiName, Map<String, Object> body) {
        int retries = Math.max(0, props.getRetryCount());
        TushareNetworkException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            if (attempt > 0) {
                long backoff = Math.max(0L, props.getRetryBackoffMillis()) << (attempt - 1);
                log.warn("Tushare 网络重试 api={} 第 {}/{} 次，等待 {}ms: {}", apiName, attempt, retries, backoff,
                        last == null ? "" : last.getMessage());
                sleep(backoff);
            }
            rateLimiter.acquire(apiName);
            try {
                String response = restClient.post()
                        .uri("/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                if (response == null) {
                    throw new TushareException("Tushare 响应为空 api=" + apiName);
                }
                return response;
            } catch (TushareException e) {
                throw e;
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status >= 500) {
                    last = new TushareNetworkException("Tushare HTTP " + status + " api=" + apiName, e);
                } else if (status == 429) {
                    throw new TushareRateLimitException("Tushare HTTP 429 频次超限 api=" + apiName);
                } else {
                    throw new TushareApiException(status, "Tushare HTTP " + status + " api=" + apiName
                            + " body=" + abbreviate(e.getResponseBodyAsString(StandardCharsets.UTF_8)));
                }
            } catch (ResourceAccessException e) {
                last = new TushareNetworkException("Tushare 网络错误 api=" + apiName + ": " + e.getMessage(), e);
            }
        }
        throw last != null ? last : new TushareNetworkException("Tushare 调用失败 api=" + apiName);
    }

    private List<Map<String, Object>> parse(String apiName, String responseBody) {
        JSONObject root;
        try {
            root = JSON.parseObject(responseBody);
        } catch (Exception e) {
            throw new TushareException("Tushare 响应不是合法 JSON api=" + apiName + ": "
                    + abbreviate(responseBody), e);
        }
        if (root == null) {
            throw new TushareException("Tushare 响应为空 api=" + apiName);
        }
        Integer code = root.getInteger("code");
        if (code == null) {
            throw new TushareException("Tushare 响应缺少 code api=" + apiName + " body=" + abbreviate(responseBody));
        }
        if (code != 0) {
            String msg = root.getString("msg");
            String text = "Tushare api=" + apiName + " code=" + code + " msg=" + msg;
            if (code == 40203) {
                throw new TushareRateLimitException("频次超限(40203) " + text);
            }
            if (code == 50101) {
                throw new TushareParamException(code, "参数错误(50101) " + text);
            }
            throw new TushareApiException(code, text);
        }
        JSONObject data = root.getJSONObject("data");
        if (data == null) {
            return List.of();
        }
        JSONArray fieldArray = data.getJSONArray("fields");
        JSONArray items = data.getJSONArray("items");
        if (fieldArray == null || items == null || items.isEmpty()) {
            return List.of();
        }
        List<String> fieldNames = new ArrayList<>(fieldArray.size());
        for (int i = 0; i < fieldArray.size(); i++) {
            fieldNames.add(fieldArray.getString(i));
        }
        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (int r = 0; r < items.size(); r++) {
            JSONArray row = items.getJSONArray(r);
            if (row == null) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            for (int j = 0; j < fieldNames.size() && j < row.size(); j++) {
                map.put(fieldNames.get(j), normalize(row.get(j)));
            }
            rows.add(map);
        }
        log.debug("Tushare api={} 返回 {} 行（响应列 {} 个）", apiName, rows.size(), fieldNames.size());
        return rows;
    }

    private static Object normalize(Object value) {
        if (value instanceof Float f) {
            return BigDecimal.valueOf(f.doubleValue());
        }
        if (value instanceof Double d) {
            return BigDecimal.valueOf(d);
        }
        return value;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TushareNetworkException("重试等待被中断", e);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 512 ? text : text.substring(0, 512) + "...";
    }
}
