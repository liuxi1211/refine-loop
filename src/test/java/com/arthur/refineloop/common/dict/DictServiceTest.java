package com.arthur.refineloop.common.dict;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.enums.YesNoEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 字典框架：枚举自动登记、排序、动态 Provider 合并、重复编码启动即失败。 */
class DictServiceTest {

    private DictService dictService;

    @BeforeEach
    void setUp() {
        dictService = new DictService("com.arthur.refineloop", List.of());
        dictService.init();
    }

    @Test
    void 实现DictEnum的枚举会被自动登记并按sort排序() {
        List<DictItem> yesNo = dictService.items(DictTypes.YES_NO);
        assertThat(yesNo).extracting(DictItem::code).containsExactly("1", "0");
        assertThat(yesNo).extracting(DictItem::label).containsExactly("是", "否");
        assertThat(yesNo.get(0).color()).isEqualTo("ok");

        assertThat(dictService.types()).contains(DictTypes.YES_NO, DictTypes.ENABLE_STATUS, DictTypes.SORT_ORDER);
    }

    @Test
    void labelOf_未知code原样返回便于展示容错() {
        assertThat(dictService.labelOf(DictTypes.YES_NO, "1")).isEqualTo("是");
        assertThat(dictService.labelOf(DictTypes.YES_NO, "unknown")).isEqualTo("unknown");
        assertThat(dictService.labelOf(DictTypes.YES_NO, null)).isNull();
    }

    @Test
    void 未知字典类型访问返回404而不是空列表() {
        assertThatThrownBy(() -> dictService.items("notExists"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(404));
        // 内部容错入口不抛异常
        assertThat(dictService.itemsOrEmpty("notExists")).isEmpty();
    }

    @Test
    void 动态Provider与枚举字典合并下发() {
        DictService withProvider = new DictService("com.arthur.refineloop",
                List.of(new TestProvider(DictTypes.JOB_TYPE, List.of(DictItem.of("INCR", "增量", 1, "ok")))));
        withProvider.init();

        assertThat(withProvider.items(DictTypes.JOB_TYPE)).extracting(DictItem::code).containsExactly("INCR");
        // 枚举字典不受影响
        assertThat(withProvider.items(DictTypes.YES_NO)).hasSize(2);
    }

    @Test
    void 枚举重复编码启动即失败() {
        // com.example.dictfixture 下两个枚举声明了同一个 dictType + code（刻意为之的夹具）
        DictService broken = new DictService("com.example.dictfixture", List.of());
        assertThatThrownBy(broken::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复编码");
    }

    @Test
    void 动态字典重复编码告警跳过不拖垮启动() {
        DictService withDuplicate = new DictService("com.arthur.refineloop",
                List.of(new TestProvider(DictTypes.YES_NO, List.of(DictItem.of("1", "重复的是")))));
        withDuplicate.init();

        // 保留枚举里的定义，动态字典的重复项被忽略
        assertThat(withDuplicate.items(DictTypes.YES_NO)).extracting(DictItem::label).containsExactly("是", "否");
    }

    @Test
    void 动态Provider异常时降级为空列表而不影响其他字典() {
        DictService resilient = new DictService("com.arthur.refineloop", List.of(new BrokenProvider()));
        resilient.init();

        assertThat(resilient.items(DictTypes.TABLE_HEALTH)).isEmpty();
        assertThat(resilient.items(DictTypes.YES_NO)).hasSize(2);
    }

    @Test
    void 枚举去重不会把同名枚举算重复() {
        assertThat(YesNoEnum.values()).hasSize(2);
        assertThat(dictService.contains(DictTypes.YES_NO, "1")).isTrue();
        assertThat(dictService.isEnabledCode(DictTypes.YES_NO, "1")).isTrue();
    }

    private record TestProvider(String dictType, List<DictItem> items) implements DictProvider {
    }

    private static final class BrokenProvider implements DictProvider {

        @Override
        public String dictType() {
            return DictTypes.TABLE_HEALTH;
        }

        @Override
        public List<DictItem> items() {
            throw new IllegalStateException("外部字典不可用");
        }
    }
}
