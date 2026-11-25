package com.arthur.refineloop.user.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密码哈希：自描述编码、每用户独立盐、常量时间校验、损坏串安全失败。
 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    @DisplayName("同一密码两次哈希不同（随机盐），但都能校验通过")
    void samePasswordProducesDifferentHashes() {
        String first = hasher.hash("admin123");
        String second = hasher.hash("admin123");

        assertThat(first).isNotEqualTo(second);
        assertThat(hasher.matches("admin123", first)).isTrue();
        assertThat(hasher.matches("admin123", second)).isTrue();
        assertThat(hasher.matches("admin124", first)).isFalse();
    }

    @Test
    @DisplayName("编码串自描述且不包含明文密码")
    void encodedValueIsSelfDescribingAndDoesNotLeakPlaintext() {
        String encoded = hasher.hash("admin123");

        assertThat(encoded).startsWith("pbkdf2$").doesNotContain("admin123");
        assertThat(encoded.split("\\$")).hasSize(4);
    }

    @Test
    @DisplayName("空值与损坏编码一律返回 false（不让登录 500）")
    void invalidInputsAreRejected() {
        assertThat(hasher.matches("", "x")).isFalse();
        assertThat(hasher.matches("admin123", null)).isFalse();
        assertThat(hasher.matches("admin123", "")).isFalse();
        assertThat(hasher.matches("admin123", "not-a-hash")).isFalse();
        assertThat(hasher.matches("admin123", "pbkdf2$abc$xx$yy")).isFalse();
        assertThat(hasher.matches("admin123", "md5$120000$xx$yy")).isFalse();
    }

    @Test
    @DisplayName("空密码不允许哈希（调用方必须先校验）")
    void blankPasswordIsRejected() {
        assertThatThrownBy(() -> hasher.hash("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.hash(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
