package com.monitor.alarm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.alarm.entity.Alarm;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AlarmMapper extends BaseMapper<Alarm> {

    /**
     * 关闭警情（进入终态）：置状态 + **释放未解除唯一键** + 可选解除时间。
     *
     * <p>为什么不用 {@code updateById}：MyBatis-Plus 默认跳过 null 字段，而关闭必须把
     * {@code open_key} 写成 NULL——写不进去的话那条警情会永久占住键位，该测点该测项
     * **后续所有警情都建不出来**（一对一的唯一索引会把它们全拒掉）。所以关闭只有这一条路。</p>
     *
     * <p>为什么带 {@code status = #{fromStatus}} 的前置条件：这是乐观并发控制。两个处置人
     * （或一个处置人 + 引擎的自动解除）同时读到 PENDING 时，后者会拿到 0 行而不是静默覆盖前者的状态。
     * 本仓没有 {@code @Version} 列也够用——警情的状态流转**每次都会改 status**，所以 status
     * 本身就是版本号，且不需要注册乐观锁拦截器。</p>
     *
     * <p>{@code resolvedAt} 可以为 null：{@code misreport} 走的是 FALSE_ALARM，
     * 按既有口径不写解除时间（沿用改造前的行为，不改动验收断言的语义）。</p>
     *
     * @return 受影响行数；0 表示状态已被他人改变，调用方应据此放弃本次写入
     */
    @Update("<script>UPDATE alarm SET status = #{toStatus}, updated_at = #{now}, open_key = NULL"
            + "<if test=\"resolvedAt != null\">, resolved_at = #{resolvedAt}</if>"
            + " WHERE id = #{id} AND status = #{fromStatus}</script>")
    int closeAlarm(@Param("id") Long id,
                   @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus,
                   @Param("now") LocalDateTime now,
                   @Param("resolvedAt") LocalDateTime resolvedAt);

    /**
     * 非终态流转（{@code confirm} / {@code dispatch} / {@code research} / {@code handle}）：
     * 只改状态，**保留 open_key**（警情仍未解除）。
     *
     * @return 受影响行数；0 表示状态已被他人改变
     */
    @Update("UPDATE alarm SET status = #{toStatus}, updated_at = #{now}"
            + " WHERE id = #{id} AND status = #{fromStatus}")
    int transitionAlarm(@Param("id") Long id,
                        @Param("fromStatus") String fromStatus,
                        @Param("toStatus") String toStatus,
                        @Param("now") LocalDateTime now);

    /**
     * 就地升级等级（不新开警情、不改所属规则、**不动 open_key**）。
     *
     * <p>前置条件同时锁住状态与等级：只锁状态的话，两个并发升级会各自基于过期的等级判断，
     * 把一次「warning -&gt; alarm」覆盖成「notice -&gt; alarm」，时间线里就出现了
     * 一次根本没发生过的降级再升。</p>
     *
     * @return 受影响行数；0 表示等级或状态已被他人改变
     */
    @Update("UPDATE alarm SET alarm_level = #{level}, updated_at = #{now}"
            + " WHERE id = #{id} AND status = #{fromStatus} AND alarm_level = #{fromLevel}")
    int escalateAlarm(@Param("id") Long id,
                      @Param("fromStatus") String fromStatus,
                      @Param("fromLevel") String fromLevel,
                      @Param("level") String level,
                      @Param("now") LocalDateTime now);
}
