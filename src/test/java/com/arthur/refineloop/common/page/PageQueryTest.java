package com.arthur.refineloop.common.page;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.PageResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 分页参数契约：默认值、边界校验、offset/limit 换算。 */
class PageQueryTest {

    @Test
    void 空参数取默认值() {
        PageQuery query = PageQuery.of(null, null);
        assertThat(query.page()).isEqualTo(PageQuery.DEFAULT_PAGE);
        assertThat(query.size()).isEqualTo(PageQuery.DEFAULT_SIZE);
        assertThat(query.offset()).isZero();
        assertThat(query.limit()).isEqualTo(20);
    }

    @Test
    void offset按从1开始换算() {
        PageQuery query = PageQuery.of(3, 20);
        assertThat(query.offset()).isEqualTo(40L);
        assertThat(query.limit()).isEqualTo(20);
    }

    @Test
    void 非法分页参数直接400不做静默截断() {
        assertThatThrownBy(() -> PageQuery.of(0, 20))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("page");
                });
        assertThatThrownBy(() -> PageQuery.of(1, 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PageQuery.of(1, PageQuery.MAX_SIZE + 1)).isInstanceOf(ApiException.class);
    }

    @Test
    void PageResults_转换MP分页与手工分页() {
        PageResult<String> manual = PageResults.of(List.of("a"), 10, PageQuery.of(1, 1));
        assertThat(manual.items()).containsExactly("a");
        assertThat(manual.total()).isEqualTo(10);

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<String> mpPage = PageResults.toPage(PageQuery.of(2, 5));
        mpPage.setRecords(List.of("x", "y"));
        mpPage.setTotal(12);
        PageResult<String> converted = PageResults.of(mpPage);
        assertThat(converted.page()).isEqualTo(2);
        assertThat(converted.size()).isEqualTo(5);
        assertThat(converted.items()).containsExactly("x", "y");
        assertThat(converted.total()).isEqualTo(12);

        assertThat(PageResults.empty(PageQuery.firstPage()).items()).isEmpty();
    }
}
