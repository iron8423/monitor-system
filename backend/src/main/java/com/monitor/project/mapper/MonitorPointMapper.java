package com.monitor.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.project.entity.MonitorPoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MonitorPointMapper extends BaseMapper<MonitorPoint> {

    /** 项目下全部有效测点，供批量最新值接口使用，避免浏览器发出 N 个请求。 */
    @Select("""
            SELECT p.*
            FROM monitor_point p
            JOIN monitor_object o ON o.id = p.object_id AND o.deleted = 0
            JOIN scene s ON s.id = o.scene_id AND s.deleted = 0
            WHERE s.project_id = #{projectId} AND p.deleted = 0
            ORDER BY p.id
            """)
    List<MonitorPoint> selectByProjectId(Long projectId);
}
