package com.arthur.refineloop.web.common;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.Result;
import com.arthur.refineloop.common.cache.CacheSpec;
import com.arthur.refineloop.common.cache.CacheTemplate;
import com.arthur.refineloop.common.cache.LocalCacheRegistry;
import com.arthur.refineloop.user.model.Permission;
import com.arthur.refineloop.web.auth.RequirePermission;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 缓存运维接口：看命中率、按需失效（数据重建后不用重启进程）。
 *
 * <ul>
 *   <li>{@code GET    /api/common/cache}            具名缓存统计 + 方法级缓存名清单；</li>
 *   <li>{@code DELETE /api/common/cache/{name}}     清空某个缓存（具名或方法级）；</li>
 *   <li>{@code POST   /api/common/cache/{name}/evict?key=} 失效单个键（仅方法级缓存支持按键清理，
 *       具名缓存的键由业务自行定义，请通过业务接口触发失效）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/common/cache")
public class CacheController {

    private final CacheTemplate cacheTemplate;
    private final LocalCacheRegistry localCacheRegistry;

    public CacheController(CacheTemplate cacheTemplate, LocalCacheRegistry localCacheRegistry) {
        this.cacheTemplate = cacheTemplate;
        this.localCacheRegistry = localCacheRegistry;
    }

    @GetMapping
    public Result<Overview> overview() {
        return Result.ok(new Overview(cacheTemplate.stats(), List.copyOf(localCacheRegistry.names())));
    }

    /** 清空缓存：具名缓存走 CacheSpec，其余按方法级缓存名处理。 */
    @DeleteMapping("/{name}")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Void> clear(@PathVariable String name) {
        CacheSpec spec = CacheSpec.byName(name).orElse(null);
        if (spec != null) {
            cacheTemplate.clear(spec);
            return Result.ok();
        }
        if (localCacheRegistry.find(name).isEmpty()) {
            throw ApiException.notFound("未知缓存: " + name);
        }
        localCacheRegistry.invalidateAll(name);
        return Result.ok();
    }

    /** 失效单个键（仅方法级缓存）。 */
    @PostMapping("/{name}/evict")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Boolean> evict(@PathVariable String name, @RequestParam String key) {
        if (CacheSpec.byName(name).isPresent()) {
            throw ApiException.badRequest("具名缓存不支持按键清理: " + name + "，请调用业务接口或在 DELETE 中清空");
        }
        if (localCacheRegistry.find(name).isEmpty()) {
            throw ApiException.notFound("未知方法级缓存: " + name);
        }
        return Result.ok(localCacheRegistry.invalidate(name, key));
    }

    /** 缓存总览。 */
    public record Overview(List<CacheTemplate.CacheStatsView> named, List<String> localCacheNames) {
    }
}
