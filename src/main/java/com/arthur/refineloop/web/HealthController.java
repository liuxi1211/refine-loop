package com.arthur.refineloop.web;

import com.arthur.refineloop.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 健康检查端点（无鉴权，公开）。
 *
 * <p>供 systemd 探活与心跳监控使用，是 {@code WebMvcConfig.PUBLIC_PATHS} 的固定成员。
 *
 * <p>外部 URL 是 {@code /api/health}，但类上<b>不写</b> {@code /api}：前缀由
 * {@code WebMvcConfig.addPathPrefix} 对 web 包统一挂载，手写会造成 {@code /api/api/health}。
 */
@RestController
public class HealthController {

    private final Clock clock;

    public HealthController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of(
                "status", "UP",
                "app", "refine-loop",
                "time", LocalDateTime.now(clock).toString()
        ));
    }
}
