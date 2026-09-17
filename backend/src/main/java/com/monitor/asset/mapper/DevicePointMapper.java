package com.monitor.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.asset.entity.DevicePoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface DevicePointMapper extends BaseMapper<DevicePoint> {

    /**
     * 把某台设备上**仍然是 ACTIVE** 的标定整批置为失效（清单第 09 条）。
     *
     * <p>集合式 UPDATE 而不是「查出来逐条 {@code updateById}」，有两个理由：</p>
     * <ul>
     *   <li>{@code invalidated_at} 由数据库一次写入**同一个值**，所以这一批失效行的时间戳
     *       天然一致，可以当作一次事件的证据来查（清单要求的「留痕」正落在这一组三元组上）；</li>
     *   <li>逐条写会退化成 N 次往返，而一台雷达挂几十个目标是常态。</li>
     * </ul>
     *
     * <p>{@code WHERE calibration_status = 'ACTIVE'} 这条守卫是方法名的一部分，
     * 刻意写死在 SQL 里而不是当参数传：{@code PENDING} 的绑定**从来没有标定过**，
     * 没有「失效」可言，把它一起写成 INVALID 会抹掉「从未标定」与「标定已失效」的区别。
     * 传进来的 {@code status} 只用于 {@code SET}。</p>
     *
     * @return 实际改动的行数（0 是正常结果：这台设备上没有生效中的标定）
     */
    @Update("""
            UPDATE device_point
               SET calibration_status = #{status},
                   invalidated_at = #{invalidatedAt},
                   invalidated_reason = #{reason},
                   invalidated_by = #{operator}
             WHERE device_id = #{deviceId} AND calibration_status = 'ACTIVE'
            """)
    int invalidateActiveOfDevice(@Param("deviceId") Long deviceId,
                                 @Param("status") String status,
                                 @Param("reason") String reason,
                                 @Param("invalidatedAt") LocalDateTime invalidatedAt,
                                 @Param("operator") String operator);

    /**
     * 测点移动时失效它身上所有生效中的标定。
     *
     * <p>单独一条而不是复用上面那条：一个测点可以被**多台**雷达观测
     * （{@code device_point.device_id} 是多对一的另一半），测点一动，
     * 所有观测它的标定的视线前提同时改变——只失效一台是漏的。</p>
     */
    @Update("""
            UPDATE device_point
               SET calibration_status = #{status},
                   invalidated_at = #{invalidatedAt},
                   invalidated_reason = #{reason},
                   invalidated_by = #{operator}
             WHERE point_id = #{pointId} AND calibration_status = 'ACTIVE'
            """)
    int invalidateActiveOfPoint(@Param("pointId") Long pointId,
                                @Param("status") String status,
                                @Param("reason") String reason,
                                @Param("invalidatedAt") LocalDateTime invalidatedAt,
                                @Param("operator") String operator);

    /**
     * 写入一次标定结果，并**清空**失效痕迹。取代原先的 {@code updateById(binding)}。
     *
     * <p>为什么不能用 {@code updateById}：它跳过 null 字段（本仓反复确认过的语义），
     * 于是三件必须发生的事都做不到——</p>
     * <ol>
     *   <li>{@code valid_to} 传 null 想表达「永久有效」，写不进去，只能**沿用上一次的有效期**。
     *       一条曾经设过 {@code validTo} 的绑定因此**再也回不到永久有效**；
     *       而「失效 -> 重新标定」这个闭环会直接踩到它。</li>
     *   <li>失效痕迹（{@code invalidated_at/reason/by}）清不掉，重新标定之后仍然显示「已失效」。</li>
     *   <li>{@code reflectorHeightM}/{@code minimumClearanceM}/{@code note} 这些可选字段同理，
     *       想置空只能靠别的途径。</li>
     * </ol>
     *
     * <p>列全写、不留隐式默认：这条 SQL 就是「一条标定由哪些字段构成」的完整声明，
     * 读它就能知道重标定会覆盖什么。新增标定字段时**必须**同步加进来——
     * 漏掉的后果是该字段静默保留旧值，而不是报错。</p>
     */
    @Update("""
            UPDATE device_point
               SET target_code = #{c.targetCode},
                   azimuth_degrees = #{c.azimuthDegrees},
                   elevation_degrees = #{c.elevationDegrees},
                   slant_range_m = #{c.slantRangeM},
                   reflector_height_m = #{c.reflectorHeightM},
                   line_of_sight = #{c.lineOfSight},
                   minimum_clearance_m = #{c.minimumClearanceM},
                   calibration_status = #{c.calibrationStatus},
                   calibrated_at = #{c.calibratedAt},
                   valid_from = #{c.validFrom},
                   valid_to = #{c.validTo},
                   calibration_note = #{c.calibrationNote},
                   invalidated_at = NULL,
                   invalidated_reason = NULL,
                   invalidated_by = NULL
             WHERE id = #{c.id}
            """)
    int applyCalibration(@Param("c") DevicePoint binding);

    /**
     * 人工停用一条标定。只作用于**已 ACTIVE** 的行，所以重复调用是幂等的
     * （第二次 0 行，不覆盖第一次留下的 {@code invalidated_at} 与操作者——
     * 那会让「谁在什么时候停用的」变成最后一次点按钮的人）。
     */
    @Update("""
            UPDATE device_point
               SET calibration_status = #{status},
                   invalidated_at = #{invalidatedAt},
                   invalidated_reason = #{reason},
                   invalidated_by = #{operator}
             WHERE device_id = #{deviceId} AND point_id = #{pointId}
               AND calibration_status = 'ACTIVE'
            """)
    int invalidateOne(@Param("deviceId") Long deviceId,
                      @Param("pointId") Long pointId,
                      @Param("status") String status,
                      @Param("reason") String reason,
                      @Param("invalidatedAt") LocalDateTime invalidatedAt,
                      @Param("operator") String operator);
}
