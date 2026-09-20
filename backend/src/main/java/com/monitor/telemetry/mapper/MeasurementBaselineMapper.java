package com.monitor.telemetry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.telemetry.entity.MeasurementBaseline;
import org.apache.ibatis.annotations.Mapper;

/** 测量基准（V24）。查询都带 point_id，走 idx_baseline_point_time。 */
@Mapper
public interface MeasurementBaselineMapper extends BaseMapper<MeasurementBaseline> {
}
