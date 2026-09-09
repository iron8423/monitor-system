package com.monitor.telemetry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.telemetry.entity.Measurement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MeasurementMapper extends BaseMapper<Measurement> {
    /** 幂等：device_id + message_id 是否已存在。 */
    @Select("SELECT COUNT(*) FROM measurement WHERE device_id = #{deviceId} AND message_id = #{messageId}")
    long countByMessageId(String deviceId, String messageId);
}
