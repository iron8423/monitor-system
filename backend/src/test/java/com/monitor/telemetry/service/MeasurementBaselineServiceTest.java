package com.monitor.telemetry.service;

import com.monitor.common.exception.BizException;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.scope.service.DataScopeService;
import com.monitor.support.MybatisPlusLambdaCache;
import com.monitor.telemetry.dto.MeasurementBaselineRequest;
import com.monitor.telemetry.entity.Measurement;
import com.monitor.telemetry.entity.MeasurementBaseline;
import com.monitor.telemetry.mapper.MeasurementBaselineMapper;
import com.monitor.telemetry.mapper.MeasurementMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 测量基准登记的判据（复查清单 P1-4）。
 *
 * <p>三条要钉住的：
 * ① 原因必须在白名单内——自由文本会让"为什么换基准"漂成每人一种写法；
 * ② 生效时间不能落到未来——一条未来的基准会让"当前基准"变成一个还没生效的东西；
 * ③ 换基准那一刻的读数要被抓下来（将来按当前基准折算的偏移基准），
 *   而当时没有数据时必须是 null 而不是 0（0 会被当成真实的零位）。</p>
 */
class MeasurementBaselineServiceTest {

    private static final Long POINT_ID = 1L;

    private MeasurementBaselineMapper baselineMapper;
    private MonitorPointMapper pointMapper;
    private MeasurementMapper measurementMapper;
    private DataScopeService dataScope;
    private MeasurementBaselineService service;

    @BeforeAll
    static void warmLambdaCaches() {
        MybatisPlusLambdaCache.warm(MeasurementBaseline.class, MonitorPoint.class, Measurement.class);
    }

    @BeforeEach
    void setUp() {
        baselineMapper = mock(MeasurementBaselineMapper.class);
        pointMapper = mock(MonitorPointMapper.class);
        measurementMapper = mock(MeasurementMapper.class);
        dataScope = mock(DataScopeService.class);
        service = new MeasurementBaselineService(baselineMapper, pointMapper, measurementMapper, dataScope);

        MonitorPoint p = new MonitorPoint();
        p.setId(POINT_ID);
        p.setCode("P-TEST");
        when(pointMapper.selectById(POINT_ID)).thenReturn(p);
        // latestRowOf 是接口的 default 方法，Mockito 的 mock 不会执行它 → 返回 null，
        // 而服务里的 `.last("LIMIT 1")` 会立刻 NPE。给一个真 wrapper 才是"正常路径"。
        when(measurementMapper.latestRowOf(any(), any(), any()))
                .thenReturn(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>());
        when(baselineMapper.selectById(any())).thenAnswer(inv -> {
            MeasurementBaseline row = new MeasurementBaseline();
            row.setId(1000L);
            row.setPointId(POINT_ID);
            row.setEffectiveFrom(LocalDateTime.of(2026, 9, 20, 10, 0));
            row.setReason("REFLECTOR_REPLACED");
            row.setOperator("admin");
            return row;
        });
    }

    @Test
    void rejectsReasonOutsideWhitelist() {
        MeasurementBaselineRequest req = request("换了个东西", null);   // 中文自由文本

        assertThatThrownBy(() -> service.create(POINT_ID, req, "admin"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    void rejectsFutureEffectiveTime() {
        MeasurementBaselineRequest req = request("DATA_RESET", LocalDateTime.now().plusHours(2));

        assertThatThrownBy(() -> service.create(POINT_ID, req, "admin"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能晚于当前时间");
    }

    /** 换基准那一刻有读数 → 抓下来当偏移基准。 */
    @Test
    void capturesReadingAtEffectiveTime() {
        Measurement row = new Measurement();
        row.setMeasureValue(3.5);
        when(measurementMapper.selectOne(any())).thenReturn(row);

        service.create(POINT_ID, request("REFLECTOR_REPLACED", null), "admin");

        // insert 有重载（单条 / 集合），argThat 推断不出类型会编译期 ambiguous；
        // 用 ArgumentCaptor 抓下来再断言，既没有歧义，报错也更具体
        MeasurementBaseline saved = captureInserted();
        assertThat(saved.getBaselineValueMm()).isNotNull();
        assertThat(new BigDecimal("3.5")).isEqualByComparingTo(saved.getBaselineValueMm());
        assertThat(saved.getOperator()).isEqualTo("admin");
    }

    /** 登记时没有数据 → baseline_value 留空（不是 0：0 会被读成真实的零位）。 */
    @Test
    void leavesValueNullWhenNoReadingYet() {
        when(measurementMapper.selectOne(any())).thenReturn(null);

        service.create(POINT_ID, request("DATA_RESET", null), "admin");

        assertThat(captureInserted().getBaselineValueMm()).isNull();
    }

    /** 同一测点同一时刻重复登记 → 400 并说明时刻（V24 的唯一约束兜底）。 */
    @Test
    void duplicateAtSameInstantIsRejected() {
        when(baselineMapper.insert(any(MeasurementBaseline.class))).thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> service.create(POINT_ID, request("MANUAL", null), "admin"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已经登记过基准变更");
    }

    /** 原因白名单是给前端做下拉用的，键必须是短码（接口调用方按短码判断）。 */
    @Test
    void exposesReasonWhitelist() {
        assertThat(MeasurementBaselineService.reasons())
                .containsKeys("REFLECTOR_REPLACED", "DEVICE_REINSTALLED", "DATA_RESET", "MANUAL");
    }

    /** 抓下 insert 的那一行（见上面 ambiguous 的说明）。 */
    private MeasurementBaseline captureInserted() {
        org.mockito.ArgumentCaptor<MeasurementBaseline> captor =
                org.mockito.ArgumentCaptor.forClass(MeasurementBaseline.class);
        verify(baselineMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static MeasurementBaselineRequest request(String reason, LocalDateTime effectiveFrom) {
        MeasurementBaselineRequest req = new MeasurementBaselineRequest();
        req.setReason(reason);
        req.setEffectiveFrom(effectiveFrom);
        return req;
    }
}
