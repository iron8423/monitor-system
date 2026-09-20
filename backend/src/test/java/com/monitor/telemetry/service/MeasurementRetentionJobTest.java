package com.monitor.telemetry.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 保留任务的分批循环（复查清单 P1-1）。
 *
 * <p>为什么单测这一层不可省：分批删除的**边界**（最后一批没取满就该停、触到单轮上限就必须停）
 * 在 HTTP 层很难构造（要真造几十万行），而写错的后果很具体——</p>
 * <ul>
 *   <li>该停不停 → 每轮固定删满上限，删不干净的表会一轮接一轮地"啃"，运维看到的 deleted 永远等于上限，
 *       分不清"还有一堆"和"已经删完"；</li>
 *   <li>不该停却停 → 一批删不完就收工，表越积越多而日志一片正常。</li>
 * </ul>
 */
class MeasurementRetentionJobTest {

    private JdbcTemplate jdbcTemplate;
    private MeasurementRetentionJob job;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        job = new MeasurementRetentionJob(jdbcTemplate);
        ReflectionTestUtils.setField(job, "batchPauseMs", 0L);   // 单测不等真实停顿
        ReflectionTestUtils.setField(job, "batchSize", 5000);
        ReflectionTestUtils.setField(job, "maxRowsPerSweep", 200_000);
        ReflectionTestUtils.setField(job, "rawDays", 90);
        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "sweepMs", 3600000L);
    }

    /** 最后一批没取满 = 删干净了：应当只发一次删除，且不再继续。 */
    @Test
    void stopsWhenBatchIsNotFull() {
        when(jdbcTemplate.update(anyString(), any(LocalDateTime.class), anyInt())).thenReturn(1200);

        Map<String, Object> stats = job.runOnce();

        assertThat(stats.get("deleted")).isEqualTo(1200L);
        assertThat(stats.get("batches")).isEqualTo(1);
        assertThat(stats.get("hitRowLimit")).isEqualTo(false);
        verify(jdbcTemplate, times(1)).update(anyString(), any(LocalDateTime.class), anyInt());
    }

    /** 连续取满就该继续：两批 5000 + 一批 300 → 三批、10300 行。 */
    @Test
    void keepsDeletingWhileBatchesAreFull() {
        when(jdbcTemplate.update(anyString(), any(LocalDateTime.class), anyInt()))
                .thenReturn(5000, 5000, 300);

        Map<String, Object> stats = job.runOnce();

        assertThat(stats.get("deleted")).isEqualTo(10300L);
        assertThat(stats.get("batches")).isEqualTo(3);
        assertThat(stats.get("hitRowLimit")).isEqualTo(false);
    }

    /** 触到单轮上限：必须停下并标记，且最后一批只申请"还差多少"（不是整批）。 */
    @Test
    void stopsAtRowLimitAndSaysSo() {
        ReflectionTestUtils.setField(job, "maxRowsPerSweep", 6000);
        when(jdbcTemplate.update(anyString(), any(LocalDateTime.class), anyInt()))
                .thenReturn(5000, 1000);

        Map<String, Object> stats = job.runOnce();

        assertThat(stats.get("deleted")).isEqualTo(6000L);
        assertThat(stats.get("hitRowLimit")).isEqualTo(true);
        // 第二批的 limit 是 1000（6000 - 5000），不是 5000
        verify(jdbcTemplate).update(anyString(), any(LocalDateTime.class), eq(1000));
    }

    /** 关闭时连一次 SQL 都不该发——这是"默认关闭"这条安全默认值的直接体现。 */
    @Test
    void disabledJobDoesNothingOnSchedule() {
        ReflectionTestUtils.setField(job, "enabled", false);

        job.sweep();

        verify(jdbcTemplate, never()).update(anyString(), any(LocalDateTime.class), anyInt());
    }

    /** 截止时间按保留窗口算：截止 = 现在 - rawDays。 */
    @Test
    void cutoffFollowsRetentionWindow() {
        ReflectionTestUtils.setField(job, "rawDays", 7);
        when(jdbcTemplate.update(anyString(), any(LocalDateTime.class), anyInt())).thenReturn(0);

        LocalDateTime before = LocalDateTime.now().minusDays(7);
        Map<String, Object> stats = job.runOnce();
        // stats 里是带偏移的 ISO 字符串（Times.iso），这里还原成墙钟时间再比
        LocalDateTime cutoff = java.time.OffsetDateTime
                .parse(String.valueOf(stats.get("cutoff")))
                .toLocalDateTime();

        // cutoff 会落在 before 与 now-7d 之间（同一次调用内取的现在）
        assertThat(cutoff).isAfter(before.minusSeconds(5)).isBefore(before.plusMinutes(1));
    }
}
