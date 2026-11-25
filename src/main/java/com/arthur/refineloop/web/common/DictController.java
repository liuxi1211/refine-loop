package com.arthur.refineloop.web.common;

import com.arthur.refineloop.common.Result;
import com.arthur.refineloop.common.dict.DictItem;
import com.arthur.refineloop.common.dict.DictService;
import com.arthur.refineloop.user.model.Permission;
import com.arthur.refineloop.web.auth.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 字典接口：前端下拉框/状态标签的唯一数据源，前端不再硬编码枚举文案。
 *
 * <p>本类属 web 适配层（包 common.* 只放基础设施，不放 @RestController）。
 *
 * <ul>
 *   <li>{@code GET  /api/common/dicts}        全量字典 {@code {type: [item]}}（首屏一次拉取，浏览器侧缓存）；</li>
 *   <li>{@code GET  /api/common/dicts/types}  类型清单；</li>
 *   <li>{@code GET  /api/common/dicts/{type}} 单个字典（按需懒加载）；</li>
 *   <li>{@code POST /api/common/dicts/refresh} 重建快照（枚举改了不需要重启时用，仅本机运维使用）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/common/dicts")
public class DictController {

    private final DictService dictService;

    public DictController(DictService dictService) {
        this.dictService = dictService;
    }

    @GetMapping
    public Result<Map<String, List<DictItem>>> all() {
        return Result.ok(dictService.all());
    }

    @GetMapping("/types")
    public Result<Set<String>> types() {
        return Result.ok(dictService.types());
    }

    @GetMapping("/{type}")
    public Result<List<DictItem>> items(@PathVariable String type) {
        return Result.ok(dictService.items(type));
    }

    @PostMapping("/refresh")
    @RequirePermission(Permission.SYSTEM_CONFIG)
    public Result<Map<String, List<DictItem>>> refresh() {
        dictService.refresh();
        return Result.ok(dictService.all(), "字典已刷新");
    }
}
