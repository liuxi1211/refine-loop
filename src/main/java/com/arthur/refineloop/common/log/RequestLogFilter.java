package com.arthur.refineloop.common.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求访问日志：<b>DEBUG 级</b>，只在开发/排障时打开。
 *
 * <p>为什么不是 INFO：一个页面打开会打十几个请求，INFO 级访问日志会把真正的业务里程碑
 * （作业完成、质量门结果、编排摘要）彻底淹没。生产默认 INFO → 本过滤器只做一次
 * {@code isDebugEnabled()} 判断，几乎没有开销；需要盯接口时把
 * {@code logging.level.com.arthur.refineloop.common.log.RequestLogFilter} 调到 DEBUG 即可。
 *
 * <p>不记录请求体/响应体：里面有 token、全量数据，落盘即事故。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/v3/api-docs")
                || uri.startsWith("/swagger-ui")
                || uri.startsWith("/favicon")
                || uri.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!log.isDebugEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            log.debug("{} {} → {} ({} ms)", request.getMethod(), request.getRequestURI(),
                    response.getStatus(), elapsedMillis);
        }
    }
}
