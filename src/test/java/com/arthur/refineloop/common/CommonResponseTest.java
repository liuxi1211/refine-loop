package com.arthur.refineloop.common;

import com.arthur.refineloop.common.error.CommonErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 统一响应/错误码/业务异常契约测试：前端依赖这些语义，改动必须是显式的。 */
class CommonResponseTest {

    @Test
    void 成功响应固定_code0_且可带自定义提示() {
        Result<String> ok = Result.ok("data");
        assertThat(ok.code()).isZero();
        assertThat(ok.msg()).isEqualTo("ok");
        assertThat(ok.data()).isEqualTo("data");

        assertThat(Result.ok().code()).isZero();
        assertThat(Result.ok().data()).isNull();
        assertThat(Result.ok(1, "已受理").msg()).isEqualTo("已受理");
    }

    @Test
    void 失败响应沿用错误码枚举的编码与文案() {
        Result<Void> result = Result.fail(CommonErrorCode.CONFLICT);
        assertThat(result.code()).isEqualTo(409);
        assertThat(result.msg()).isEqualTo(CommonErrorCode.CONFLICT.message());

        Result<Void> custom = Result.fail(CommonErrorCode.NOT_FOUND, "未知数据表: foo");
        assertThat(custom.code()).isEqualTo(404);
        assertThat(custom.msg()).isEqualTo("未知数据表: foo");
    }

    @Test
    void map_只在成功时转换数据() {
        assertThat(Result.ok(2).<String>map(v -> "v" + v).data()).isEqualTo("v2");

        Result<String> failed = Result.<Integer>fail(CommonErrorCode.INTERNAL_ERROR).map(v -> "never");
        assertThat(failed.code()).isEqualTo(500);
        assertThat(failed.data()).isNull();
    }

    @Test
    void 业务异常携带错误码且文案可覆盖() {
        assertThat(ApiException.badRequest("x").getCode()).isEqualTo(400);
        assertThat(ApiException.notFound("x").getCode()).isEqualTo(404);
        assertThat(ApiException.conflict("x").getCode()).isEqualTo(409);
        assertThat(ApiException.unavailable("x").getCode()).isEqualTo(503);
        assertThat(ApiException.reject("x").getCode()).isEqualTo(1001);
        assertThat(ApiException.of(CommonErrorCode.DATA_NOT_READY, "交易日历未就绪").getMessage())
                .isEqualTo("交易日历未就绪");
        // 兼容早期裸 code 构造
        assertThat(new ApiException(429, "限流").getCode()).isEqualTo(429);
    }

    @Test
    void 通用错误码全局唯一且只有成功码为0() {
        Set<Integer> codes = Arrays.stream(CommonErrorCode.values())
                .map(CommonErrorCode::code)
                .collect(Collectors.toSet());
        assertThat(codes).hasSameSizeAs(CommonErrorCode.values());
        assertThat(Arrays.stream(CommonErrorCode.values()).filter(code -> code.code() == 0).toList())
                .containsExactly(CommonErrorCode.OK);
        // 沟通层编码必须与 HTTP 语义一致，前端按此分支
        assertThat(CommonErrorCode.BAD_REQUEST.code()).isEqualTo(400);
        assertThat(CommonErrorCode.NOT_FOUND.code()).isEqualTo(404);
        assertThat(CommonErrorCode.INTERNAL_ERROR.code()).isEqualTo(500);
    }

    @Test
    void 分页结果提供分页信息与类型转换() {
        PageResult<Integer> page = PageResult.of(java.util.List.of(1, 2, 3), 7, 2, 3);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.totalPages()).isEqualTo(3);

        PageResult<String> mapped = page.map(String::valueOf);
        assertThat(mapped.items()).containsExactly("1", "2", "3");
        assertThat(mapped.total()).isEqualTo(7);

        assertThat(PageResult.empty(1, 20).items()).isEmpty();
        assertThat(PageResult.of(null, 0, 1, 20).items()).isEmpty();
    }

    @Test
    void 业务异常不吞掉原始cause() {
        IllegalStateException cause = new IllegalStateException("boom");
        ApiException exception = new ApiException(CommonErrorCode.INTERNAL_ERROR, "包装", cause);
        assertThat(exception.getCause()).isSameAs(cause);
        assertThatThrownBy(() -> {
            throw exception;
        }).hasCause(cause);
    }
}
