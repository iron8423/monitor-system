package com.monitor.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.asset.entity.Device;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DeviceMapper extends BaseMapper<Device> {

    /** 项目绑定设备（去重），供数字孪生绘制雷达位姿；避免先扫全库再逐台查询归属。 */
    @Select("""
            SELECT DISTINCT d.*
            FROM device d
            JOIN device_point dp ON dp.device_id = d.id
            JOIN monitor_point p ON p.id = dp.point_id AND p.deleted = 0
            JOIN monitor_object o ON o.id = p.object_id AND o.deleted = 0
            JOIN scene s ON s.id = o.scene_id AND s.deleted = 0
            WHERE s.project_id = #{projectId} AND d.deleted = 0
            ORDER BY d.id
            """)
    List<Device> selectByProjectId(Long projectId);
}
