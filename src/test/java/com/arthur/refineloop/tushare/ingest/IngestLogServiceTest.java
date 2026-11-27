package com.arthur.refineloop.tushare.ingest;

import com.arthur.refineloop.data.mapper.IngestLogMapper;
import com.arthur.refineloop.support.MutableClock;
import com.arthur.refineloop.support.SchemaTestSupport;
import com.arthur.refineloop.tushare.ingest.service.IngestLogService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class IngestLogServiceTest {

    @Test
    void startupRecoveryMarksInterruptedJobsFailedWithoutChangingSuccessfulHistory() {
        JdbcTemplate jdbc = SchemaTestSupport.newJdbcTemplate();
        MutableClock clock = MutableClock.at("2026-11-26T10:00:00Z");
        IngestLogMapper mapper = SchemaTestSupport.mapper(jdbc, IngestLogMapper.class);
        IngestLogService service = new IngestLogService(mapper, clock);
        long runningId = service.createRunning("R-OLD", "daily", "BACKFILL", "19901219~20261126");
        service.createSkipped("R-OLD", "hk_hold", "INCR", "-", "停用");
        service.markSuccess(service.createRunning("R-DONE", "trade_cal", "FULL", "19901219~20261126"),
                10, 10, clock.instant().atZone(clock.getZone()).toLocalDateTime(),
                clock.instant().atZone(clock.getZone()).toLocalDateTime());
        clock.advance(60_000);

        int recovered = service.recoverInterruptedJobs();

        assertThat(recovered).isEqualTo(1);
        assertThat(service.findById(runningId)).hasValueSatisfying(job -> {
            assertThat(job.status()).isEqualTo("FAILED");
            assertThat(job.errorMsg()).contains("服务重启").contains("重试");
            assertThat(job.retryCount()).isZero();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ingest_log WHERE status = 'RUNNING'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ingest_log WHERE status = 'SUCCESS'", Long.class))
                .isEqualTo(1L);
        assertThat(service.recoverInterruptedJobs()).as("恢复必须幂等").isZero();
    }
}
