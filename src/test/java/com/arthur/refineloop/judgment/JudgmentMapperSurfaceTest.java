package com.arthur.refineloop.judgment;

import com.arthur.refineloop.data.mapper.JudgmentMapper;
import com.arthur.refineloop.data.mapper.JudgmentUnitRefMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冻结语义的<b>编译期</b>护栏：{@code judgment} / {@code judgment_unit_ref} 的 Mapper 只允许指定的写方法。
 *
 * <p>为什么用反射锁白名单：判断「存档后不可改」这条不变量，靠的是「代码里没有更新入口」。
 * 只要有人给 Mapper 加一个 {@code updatePayload}，或者在接口上补 {@code extends BaseMapper}
 * （会一次带来 updateById / update / deleteById 全家桶），冻结语义就静默失效——而这种改动
 * 编译期、运行期都不会报错，只有归因时发现「历史判断被改写了」才暴露。
 *
 * <p>规则：
 * <ol>
 *   <li>不继承 {@code BaseMapper}（否则通用写方法全部可用）；</li>
 *   <li>方法名必须在白名单内（增删方法必须同时更新本测试与需求，属刻意的摩擦）；</li>
 *   <li>不存在 {@code delete*} / {@code remove*} 方法（判断记录不可删除）。</li>
 * </ol>
 */
@DisplayName("判断 Mapper 表面：冻结语义只允许两个标记列被更新")
class JudgmentMapperSurfaceTest {

    /** 允许出现在 {@code JudgmentMapper} 上的方法：1 写 + 4 读 + 2 标记。 */
    private static final Set<String> ALLOWED_JUDGMENT_METHODS = Set.of(
            "insert",
            "selectByJudgmentId",
            "selectActive",
            "selectLatestJudgmentId",
            "countPriorActiveSameDirection",
            "markSuperseded",
            "markInvalidated");

    /** 允许出现在 {@code JudgmentUnitRefMapper} 上的方法：写入一次，之后只读。 */
    private static final Set<String> ALLOWED_UNIT_REF_METHODS = Set.of(
            "insert",
            "selectByJudgmentId");

    @Test
    @DisplayName("JudgmentMapper 不继承 BaseMapper（否则通用 update/delete 全部可用）")
    void judgmentMapperDoesNotExtendBaseMapper() {
        List<Class<?>> parents = Arrays.asList(JudgmentMapper.class.getInterfaces());
        assertThat(parents).as("JudgmentMapper 只应继承 Mapper 标记接口，不得继承 BaseMapper")
                .noneMatch(parent -> parent.getName().contains("BaseMapper"));
        assertThat(JudgmentUnitRefMapper.class.getInterfaces())
                .as("JudgmentUnitRefMapper 不得继承 BaseMapper")
                .noneMatch(parent -> parent.getName().contains("BaseMapper"));
    }

    @Test
    @DisplayName("JudgmentMapper 方法白名单：只有指定方法存在")
    void judgmentMapperOnlyExposesWhitelistedMethods() {
        assertThat(methodNames(JudgmentMapper.class))
                .as("JudgmentMapper 出现了未登记的方法：任何新增写方法都会破坏冻结语义")
                .containsExactlyInAnyOrderElementsOf(ALLOWED_JUDGMENT_METHODS);
    }

    @Test
    @DisplayName("JudgmentUnitRefMapper 方法白名单：引用行写入后只读")
    void unitRefMapperOnlyExposesWhitelistedMethods() {
        assertThat(methodNames(JudgmentUnitRefMapper.class))
                .containsExactlyInAnyOrderElementsOf(ALLOWED_UNIT_REF_METHODS);
    }

    @Test
    @DisplayName("不存在 delete/remove 方法：判断记录只能标注失效，不能删除")
    void noDeleteMethods() {
        Set<String> names = methodNames(JudgmentMapper.class);
        names.addAll(methodNames(JudgmentUnitRefMapper.class));

        assertThat(names).as("判断资产不可删除（总纲 5.1）")
                .noneMatch(name -> name.startsWith("delete") || name.startsWith("remove"));
    }

    @Test
    @DisplayName("更新方法只作用于 superseded / invalidated_at 两列")
    void onlyTwoColumnsAreUpdatable() {
        List<String> updateStatements = Arrays.stream(JudgmentMapper.class.getMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("mark"))
                .toList();

        assertThat(updateStatements).containsExactlyInAnyOrder("markSuperseded", "markInvalidated");
    }

    private static Set<String> methodNames(Class<?> mapperType) {
        return Arrays.stream(mapperType.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
    }
}
