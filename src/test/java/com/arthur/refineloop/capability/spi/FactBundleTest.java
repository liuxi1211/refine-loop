package com.arthur.refineloop.capability.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FactBundle：两段式事实视图")
class FactBundleTest {

    @Test
    @DisplayName("并发写入后 seal，视图只读且事实齐全")
    void concurrentPutThenSeal() throws Exception {
        FactBundle.Builder builder = FactBundle.builder("000001.SZ", "20261126", "run-1");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 100; i++) {
                String unitId = "unit_" + i;
                executor.submit(() -> builder.put(fact("000001.SZ", unitId)));
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        FactBundle bundle = builder.seal();
        assertThat(bundle.isSealed()).isTrue();
        assertThat(bundle.size()).isEqualTo(100);
        assertThat(bundle.tsCode()).isEqualTo("000001.SZ");
        assertThat(bundle.tradeDate()).isEqualTo("20261126");
        assertThat(bundle.fact("unit_1").payload()).containsEntry("value", 1);
        assertThat(bundle.requireFact("unit_1", Map.class)).containsEntry("value", 1);
        assertThatThrownBy(() -> bundle.all().put("x", fact("000001.SZ", "x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("seal 后禁止写入，重复 unitId 禁止写入")
    void sealAndDuplicateAreRejected() {
        FactBundle.Builder builder = FactBundle.builder("000001.SZ", "20261126", "run-1");
        builder.put(fact("000001.SZ", "unit_a"));
        assertThatThrownBy(() -> builder.put(fact("000001.SZ", "unit_a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复写入");

        FactBundle bundle = builder.seal();
        assertThatThrownBy(() -> builder.put(fact("000001.SZ", "unit_b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已冻结");
        assertThat(bundle.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("事实 tsCode 必须与视图一致")
    void tsCodeMustMatch() {
        FactBundle.Builder builder = FactBundle.builder("000001.SZ", "20261126", "run-1");
        assertThatThrownBy(() -> builder.put(fact("600000.SH", "unit_a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tsCode");
    }

    private FactResult fact(String tsCode, String unitId) {
        return new FactResult(tsCode, unitId, "v1.0", "v1.0",
                FactDataStatus.COMPLETE, Map.of("value", 1), List.of());
    }
}
