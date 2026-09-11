package com.monitor.alarm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.alarm.entity.Alarm;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AlarmMapper extends BaseMapper<Alarm> {
}
