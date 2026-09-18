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

    /**
     * 测点归属的项目 id（测点 → 对象 → 场景 上溯）。链路断掉（对象/场景缺失、被软删）返回 {@code null}。
     *
     * <p>给告警引擎的项目级规则匹配用（V22）。刻意写成一条 JOIN 而不是复用
     * {@code DataScopeService#projectIdsOfPoint}：那条路是三次 {@code selectById}，
     * 而这里处在"每条测值都要走"的热路径上，且**只在确实存在项目级规则时**才被调用
     * （见 {@code AlarmEngine#enabledRulesFor}）。</p>
     *
     * <p>软删条件与 {@link #selectByProjectId} 逐条对齐：三张表都可能被逻辑删除，
     * 少写一条就会让一个已删对象的测点"仍属于"某个项目，从而套上那个项目的阈值。</p>
     */
    @Select("""
            SELECT s.project_id
            FROM monitor_point p
            JOIN monitor_object o ON o.id = p.object_id AND o.deleted = 0
            JOIN scene s ON s.id = o.scene_id AND s.deleted = 0
            WHERE p.id = #{pointId} AND p.deleted = 0
            """)
    Long selectProjectIdOfPoint(Long pointId);
}
