package com.monitor.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.project.entity.Metric;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MetricMapper extends BaseMapper<Metric> {
}
