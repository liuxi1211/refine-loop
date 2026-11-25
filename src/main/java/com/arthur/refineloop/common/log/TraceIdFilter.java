package com.arthur.refineloop.common.log;

import com.arthur.refineloop.common.constant.HeaderNames;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 链路号过滤器：为每个请求建立 traceId，并写回响应头，前端报错提示可以直接带上它。
 *
 * <p>规则：
 * <ul>
 *   <li>上游带了合法 {@code X-Trace-Id} 就复用（便于「前端日志 ↔ 后端日志」对齐）；</li>
 *   <li>缺失或格式不合法（超长/特殊字符）一律重新生成，避免日志注入与脏数据；</li>
 *   <li>无论请求成功失败都在 finally 清理 MDC，线程复用时不会串号。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    static final String TRACE_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HeaderNames.TRACE_ID);
        String traceId = TraceContext.isValidTraceId(incoming) ? incoming : TraceContext.newTraceId();
        request.setAttribute(TRACE_ATTRIBUTE, traceId);
        response.setHeader(HeaderNames.TRACE_ID, traceId);
        try (TraceContext.Scope ignored = TraceContext.withTraceId(traceId)) {
            chain.doFilter(request, response);
        }
    }

    /** 供控制器/日志取当前请求的 traceId（过滤器已写入 MDC，这里只是类型化访问）。 */
    public static String traceIdOf(HttpServletRequest request) {
        Object value = request.getAttribute(TRACE_ATTRIBUTE);
        return value == null ? TraceContext.currentTraceIdOrNone() : value.toString();
    }
}
