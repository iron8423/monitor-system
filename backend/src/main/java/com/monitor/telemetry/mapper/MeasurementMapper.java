package com.monitor.telemetry.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.telemetry.dto.IngestMode;
import com.monitor.telemetry.dto.MeasurementBucket;
import com.monitor.telemetry.dto.MeasurementPointBucket;
import com.monitor.telemetry.entity.Measurement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MeasurementMapper extends BaseMapper<Measurement> {

    /**
     * 一次取得多测点各自最新消息中的任意一行。相关子查询利用
     * idx_measurement_point_time，适合首期 1000 测点快照。
     *
     * <p>只认 {@code ingest_mode = 'REALTIME'} 的行（字面量与 {@link IngestMode#REALTIME} 同值，
     * 该取值另有 {@code ck_measurement_ingest_mode} 约束兜底）。理由与
     * {@link #recentOfDevice} 一致：补报写的是几天前的 collectTime，但排序只看 collectTime，
     * 于是「刚补进来的历史行」会直接成为这个测点的**当前值**，压住真正的实时数据。
     * 补报不影响告警与心跳早已由 {@code IngestService} 的 REALTIME 闸门保证（V12），
     * 这里是同一条规则在查询侧的补齐。</p>
     *
     * <p>V12 把该列建成 {@code NOT NULL DEFAULT 'REALTIME'}，所以 V12 之前的行全是 REALTIME，
     * 不存在「老数据因此查不到」的问题。</p>
     *
     * <p>{@code ceiling} = 当前值的时间上界（清单第 10 条），与
     * {@link #latestRowOf} 同一条规则的第三个从句：只有 REALTIME、且 {@code collect_time}
     * 不超前于现在，才是「当前值」。理由与取值见 {@link #latestRowOf}。</p>
     */
    @Select("""
            <script>
            SELECT m.*
            FROM measurement m
            WHERE m.point_id IN
              <foreach collection="pointIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              AND m.id = (
                SELECT m2.id
                FROM measurement m2
                WHERE m2.point_id = m.point_id AND m2.collect_time IS NOT NULL
                  AND m2.ingest_mode = 'REALTIME'
                  AND m2.collect_time &lt;= #{ceiling}
                ORDER BY m2.collect_time DESC, m2.id DESC
                LIMIT 1
              )
            </script>
            """)
    List<Measurement> latestRowsOfPoints(@Param("pointIds") List<Long> pointIds,
                                         @Param("ceiling") LocalDateTime ceiling);

    /** 批量补齐一条标准消息拆出的全部测项行。 */
    @Select("""
            <script>
            SELECT * FROM measurement
            WHERE message_id IN
              <foreach collection="messageIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY point_id, metric_code
            </script>
            """)
    List<Measurement> rowsByMessageIds(@Param("messageIds") List<String> messageIds);

    /**
     * 按时间桶取均值（P0-3 的 ③：分桶下沉到 SQL）。
     *
     * <p>{@code DATE_TRUNC} 在 H2（本地/验收）与 PostgreSQL（生产）上都有，
     * 且 {@code collect_time} 是**不带时区**的 TIMESTAMP，两个库都只做字面截断、
     * 不做时区换算——与改造前在 Java 里 {@code truncatedTo(HOURS/DAYS)} 的结果逐值一致。
     * 这正是本项目一直遵守的"同一份 SQL 跑两个库"的做法（V14 的 open_key 也是一例）。</p>
     *
     * <p><b>为什么单位写成两段字面量而不是参数/占位符</b>（实测教训，2026-09-18）：
     * H2 2.3.232 的 {@code DATE_TRUNC} **要求第一个参数是字面量**，写成 {@code DATE_TRUNC(?, ts)}
     * 直接报 {@code Syntax error ... expected "date-time field"}（而 PostgreSQL 允许参数）。
     * 所以这里用 MyBatis 的 choose 分支写死 {@code 'HOUR'}/{@code 'DAY'} 两个字面量，
     * 由布尔参数选一支——既不引入 <code>${}</code> 拼接（那才是有注入面的写法），
     * 也保证两个库上跑的是同一份 SQL。表达式因此出现两次（SELECT 与 GROUP BY），
     * 这是聚合走索引的代价，不要"顺手"抽成一个别名。</p>
     *
     * <p>为什么不让数据库算平均、反而原来那样在内存里分组：生产基线 1000 点 × 5 秒，
     * 一个月就是十亿级；一个"近 31 天、day 粒度"的请求如果先把原始行全拉回 JVM，
     * 需要搬 50 万行来画 31 个点。聚合下推之后，搬回来的行数等于桶数。</p>
     */
    @Select("""
            <script>
            SELECT <choose><when test="daily">DATE_TRUNC('DAY', collect_time)</when><otherwise>DATE_TRUNC('HOUR', collect_time)</otherwise></choose> AS bucket_time,
                   AVG(measure_value) AS bucket_value
            FROM measurement
            WHERE point_id = #{pointId}
              AND metric_code = #{metricCode}
              AND collect_time &gt;= #{from}
              AND collect_time &lt;= #{to}
            GROUP BY <choose><when test="daily">DATE_TRUNC('DAY', collect_time)</when><otherwise>DATE_TRUNC('HOUR', collect_time)</otherwise></choose>
            ORDER BY 1
            </script>
            """)
    List<MeasurementBucket> averageByBucket(@Param("pointId") Long pointId,
                                            @Param("metricCode") String metricCode,
                                            @Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to,
                                            @Param("daily") boolean daily);

    /**
     * 批量原始点（P2-4）：一次查询取多个测点的窗口内原始行。
     *
     * <p><b>为什么不是"循环调用单点版本"</b>：回放原本对每个测点各发一次请求
     * （用并发池压住瞬时压力），生产基线 1000 点时首屏就是 1000 次查询——
     * 并发池只改变了"同时几个"，没改变"总共几次"。一条 {@code IN} 查询把它变成几次。</p>
     *
     * <p>排序是 {@code point_id, collect_time}：分组靠 Map，但每点内部的时序必须在
     * SQL 里就定好，否则曲线会按数据库的返回顺序连线。</p>
     *
     * <p>{@code limit} 是"总量上限 + 1"：拿满上限恰好合法，多出来那一行才说明被截断——
     * 与单点 {@code rawPoints} 同一个探测法。</p>
     */
    @Select("""
            <script>
            SELECT * FROM measurement
            WHERE point_id IN
              <foreach collection="pointIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              AND metric_code = #{metricCode}
              AND collect_time &gt;= #{from}
              AND collect_time &lt;= #{to}
            ORDER BY point_id, collect_time, id
            LIMIT #{limit}
            </script>
            """)
    List<Measurement> batchRawRows(@Param("pointIds") List<Long> pointIds,
                                   @Param("metricCode") String metricCode,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to,
                                   @Param("limit") int limit);

    /**
     * 批量分桶均值（P2-4）：与 {@link #averageByBucket} 同一条 SQL，多一个 {@code point_id}
     * 维度、返回行里带 {@code point_id}。
     *
     * <p>行数等于"点数 × 桶数"，与单点版本一样是聚合后的规模——批量化不会把原始行搬回来。</p>
     */
    @Select("""
            <script>
            SELECT point_id AS point_id,
                   <choose><when test="daily">DATE_TRUNC('DAY', collect_time)</when><otherwise>DATE_TRUNC('HOUR', collect_time)</otherwise></choose> AS bucket_time,
                   AVG(measure_value) AS bucket_value
            FROM measurement
            WHERE point_id IN
              <foreach collection="pointIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              AND metric_code = #{metricCode}
              AND collect_time &gt;= #{from}
              AND collect_time &lt;= #{to}
            GROUP BY point_id,
                     <choose><when test="daily">DATE_TRUNC('DAY', collect_time)</when><otherwise>DATE_TRUNC('HOUR', collect_time)</otherwise></choose>
            ORDER BY point_id, 2
            </script>
            """)
    List<MeasurementPointBucket> averageByBucketForPoints(@Param("pointIds") List<Long> pointIds,
                                                          @Param("metricCode") String metricCode,
                                                          @Param("from") LocalDateTime from,
                                                          @Param("to") LocalDateTime to,
                                                          @Param("daily") boolean daily);

    /** 幂等：device_id + message_id 是否已存在。撞唯一键后用它区分「真重复」与「暂时失败」。 */
    @Select("SELECT COUNT(*) FROM measurement WHERE device_id = #{deviceId} AND message_id = #{messageId}")
    long countByMessageId(@Param("deviceId") String deviceId, @Param("messageId") String messageId);

    /**
     * 某台设备在窗口内**收到**的测量值（数据可信度判定用）。
     *
     * <p>{@code deviceId} 传的是**设备码**（{@code device.code}）不是数字主键——
     * {@code measurement.device_id} 是 {@code VARCHAR(64)}、存的是上报报文里那个设备码，
     * 而 {@code device_point.device_id} 才是 {@code BIGINT}。同名不同物，混用会静默查出空集
     * （不报错、只是判据永远不成立），是最容易写错又最难发现的一处。</p>
     *
     * <p>只统计 {@code ingest_mode=REALTIME}。补报即使刚写入数据库，也不是设备的当前链路状态。
     * 窗口卡在 {@code receive_time}（平台侧收到的时间）而不是 {@code collect_time}：
     * 「最近收到的数据」才是这条链路的当下状态；按采集时间卡的话，
     * 实时消息是否迟到要比较这两个时间；历史补报已在模式闸门外排除。</p>
     */
    default List<Measurement> recentOfDevice(String deviceCode, LocalDateTime since) {
        return selectList(new LambdaQueryWrapper<Measurement>()
                .eq(Measurement::getDeviceId, deviceCode)
                .eq(Measurement::getIngestMode, "REALTIME")
                .ge(Measurement::getReceiveTime, since));
    }

    /**
     * 「取某测点最新一行」的**唯一**查询口径：{@code collect_time DESC, id DESC}。
     *
     * <p>{@code collect_time} 相同时必须再有确定的次序。同一点在同一采集时刻落两行是正常的——
     * 幂等键是 {@code device_id + message_id}，不含 {@code collect_time}，所以设备重传换了 messageId、
     * 或两台设备覆盖同一点，都会留下同刻的两行，这不是「重复上报」。此时只按 {@code collect_time}
     * 排序的话，取到哪一行由**数据库返回顺序**决定，而数据库返回顺序取决于执行计划：实测同一个库，
     * 走索引反向扫时恰好先给到高 id（于是看着一切正常），一旦退化成全表扫（小表、库刚建好、
     * H2 内存库）就先给到先写的那行——同一份代码在两个计划下给出两个答案。</p>
     *
     * <p>本方法把排序收在一处：{@code MeasurementQueryService#latest}（最新测项）与
     * {@code ProjectSummaryService#maxDeformation}（最新形变）曾经各写各的排序，一处带 id 兜底、
     * 一处不带，于是同一测点在 {@code /points/{id}/latest} 与 {@code /projects/{id}/summary}
     * 上给出两个值。加筛选条件请在此之后调 {@code .eq(...)} 之前想清楚顺序——
     * MyBatis-Plus 按调用顺序拼 SQL，{@code orderBy} 之后再加条件会拼到 ORDER BY 后面。</p>
     *
     * <p>兜底用 {@code id} 而不是 {@code receive_time}：主键单调递增，等价于「后写库的覆盖先写的」，
     * 且不像 {@code receive_time} 那样有 NULL 排序的方言差异（PG 的 DESC 把 NULL 排最前、H2 排最后）。</p>
     *
     * <p>同样只认 {@code ingest_mode = REALTIME}（见 {@link #latestRowsOfPoints}）：
     * 这是「当前值」的口径，补报的历史行不是当前值。只有补报数据的测点会因此显示「暂无数据」，
     * 那正是它应有的样子。</p>
     *
     * <p><b>{@code ceiling} 是同一个口径的第三个从句</b>（清单第 10 条）：只有 REALTIME、
     * 且 {@code collect_time} **不超前于现在**的行，才是「当前值」。没有它，一条
     * {@code collect_time = 2099} 的行会无条件赢得 {@code collect_time DESC}，把
     * {@code /points/{id}/latest}、项目快照与 {@code maxDeformation} 永久钉在那一行上——
     * 之后所有正常上报都读不出来。接入侧已经拒收未来采集时间（{@code COLLECT_TIME_IN_FUTURE}），
     * 这里是查询侧的兜底：它护的是**表里已有的**行——运维手工 SQL 写的、备份恢复回来的、
     * 上一个版本写入的、以及部署与迁移之间的窗口。两侧都要有。</p>
     *
     * <p><b>刻意不留 2 参重载。</b>这块地方上一次漂移就是「一个调用点有 id 兜底、另一个没有」
     * （见上），而一个隐式默认的 ceiling 正是下一次漂移的起点——传 {@code null} 会让条件
     * 静默消失，那比编译不过危险得多。宁可让三个调用点各自显式写出自己的上界。</p>
     *
     * @param metricCode 只取该测项；传 {@code null} 表示不限测项
     * @param ceiling    当前值的时间上界；传 {@code LocalDateTime.now()}。理论上可为 null
     *                   （条件消失），但**没有任何调用点该这么做**
     */
    default LambdaQueryWrapper<Measurement> latestRowOf(Long pointId, String metricCode,
                                                        LocalDateTime ceiling) {
        return new LambdaQueryWrapper<Measurement>()
                .eq(Measurement::getPointId, pointId)
                .eq(metricCode != null, Measurement::getMetricCode, metricCode)
                // 必须排在 orderBy 之前：MyBatis-Plus 按调用顺序拼 SQL，
                // orderBy 之后再加条件会拼到 ORDER BY 后面（见上面的类注释）。
                .eq(Measurement::getIngestMode, IngestMode.REALTIME.name())
                .isNotNull(Measurement::getCollectTime)
                .le(Measurement::getCollectTime, ceiling)
                .orderByDesc(Measurement::getCollectTime)
                .orderByDesc(Measurement::getId);
    }
}
