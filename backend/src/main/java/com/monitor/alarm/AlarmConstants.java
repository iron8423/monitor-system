package com.monitor.alarm;

import com.monitor.common.exception.BizException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 告警枚举与状态机。
 * <p>枚举值冻结于 M0（D5）；<b>动作 -&gt; 状态</b> 的映射由 A 于 2026-09-10 定案
 * （契约只列了两个枚举，没写映射关系）：
 * 待确认 →(confirm) 已确认 →(dispatch) 处置中 →(research) 观察中 →(resolve) 已解除；
 * {@code handle} 与 {@code dispatch} 同为处置中；{@code misreport} 任意态转误报。</p>
 */
public final class AlarmConstants {

    private AlarmConstants() {
    }

    public static final String PENDING = "PENDING";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String PROCESSING = "PROCESSING";
    public static final String OBSERVING = "OBSERVING";
    public static final String RESOLVED = "RESOLVED";
    public static final String FALSE_ALARM = "FALSE_ALARM";

    /** 终态：不再算作「未解除警情」。 */
    public static final Set<String> CLOSED = Set.of(RESOLVED, FALSE_ALARM);

    /** 警情来源：挂测点的形变警情 / 挂设备的状态告警。 */
    public static final String TYPE_POINT = "POINT";
    public static final String TYPE_DEVICE = "DEVICE";

    /**
     * {@code DEVICE} 类警情的**成因**（V7 落库，见 {@code V7__alarm_reason.sql}）。
     *
     * <p>不新开 {@code alarm_type} 而细分 {@code alarm_reason}：三类成因讲的都是「设备侧的问题」，
     * 共用同一张表、同一套状态机与处置管线正是 B-10 当初选「复用一张表」要兑现的收益；
     * 新开枚举则要改契约 §4、波及四端过滤、前端映射与既有断言，换不来任何东西。</p>
     *
     * <p>但**必须区分**，不能都叫 DEVICE 就完事：一台设备可以同时欠着「数据不可信」与
     * 「已经掉线」两条警情，两个监视器各按 {@code (deviceId, alarmReason)} 找未解除警情，
     * 否则后到的成因会被先到的掩盖住。</p>
     */
    public static final String REASON_OFFLINE = "OFFLINE";
    public static final String REASON_DATA_QUALITY = "DATA_QUALITY";
    public static final String REASON_DATA_DELAY = "DATA_DELAY";

    /**
     * 未解除唯一键的**唯一**构造处（V14，见 {@code V14__alarm_open_key.sql}）。
     *
     * <p>四条开警情的路径（{@code AlarmEngine.trigger} / {@code DeviceAlarmMonitor.raise} /
     * {@code DataQualityMonitor.raise}）与 V14 迁移必须拼出**逐字节相同**的键，
     * 否则唯一索引形同虚设、存量收敛也认不出新行。所以只在这里定义一次。</p>
     *
     * <p>任一成分缺失就返回 null（与迁移里的 {@code ELSE NULL} 同一口径）：
     * 拿不到测项的历史警情（{@code rule_id} 悬空）本来就不该参与唯一性，
     * 拼成 {@code 'P:1001:null'} 会把一批互不相关的警情判成同一条。</p>
     *
     * <p><b>为什么键里是「测点 + 测项」而不是「测点 + 规则」</b>：升级功能依赖同一测项下
     * 存在多条不同等级的规则，用规则做键会让同测项的两条规则各开一条警情——
     * 正是「至多一条」要消灭的东西。测项是**副本**，规则可改可删，见
     * {@code Alarm.metricCode} 的说明。</p>
     */
    public static String openKeyOfPoint(Long pointId, String metricCode) {
        if (pointId == null || metricCode == null || metricCode.isEmpty()) {
            return null;
        }
        return "P:" + pointId + ":" + metricCode;
    }

    /** 设备警情的未解除唯一键：设备 + **成因**（同一台设备可以同时欠着离线与数据质量两条）。 */
    public static String openKeyOfDevice(Long deviceId, String reason) {
        if (deviceId == null || reason == null || reason.isEmpty()) {
            return null;
        }
        return "D:" + deviceId + ":" + reason;
    }

    /** 系统自动动作（非人工处置）。 */
    public static final String ACTION_TRIGGER = "trigger";
    public static final String ACTION_RECOVER = "recover";
    /**
     * 升级：同一测点已有未解除警情时，更高等级的规则触发不再另开一条，而是就地抬高等级
     * （验收第 3 条「等级升高能升级」）。与 trigger/recover 一样是系统动作，不在处置动作枚举里。
     */
    public static final String ACTION_ESCALATE = "escalate";
    public static final String SYSTEM = "system";

    /** 等级由弱到强（D5 冻结枚举）。 */
    private static final List<String> LEVELS = List.of("notice", "warning", "alarm");

    /**
     * 等级强弱序号，用于比较「谁更高」。未知 / null 一律返回 -1（最低），
     * 这样一条等级写错的规则不会把已有警情降级。
     */
    public static int rankOf(String level) {
        if (level == null) {
            return -1;
        }
        int i = LEVELS.indexOf(level.trim().toLowerCase());
        return i < 0 ? -1 : i;
    }

    private static final Map<String, String> ACTION_TO_STATUS = new LinkedHashMap<>();

    static {
        ACTION_TO_STATUS.put("confirm", CONFIRMED);
        ACTION_TO_STATUS.put("dispatch", PROCESSING);
        ACTION_TO_STATUS.put("research", OBSERVING);
        ACTION_TO_STATUS.put("handle", PROCESSING);
        ACTION_TO_STATUS.put("resolve", RESOLVED);
        ACTION_TO_STATUS.put("misreport", FALSE_ALARM);
    }

    /** 处置动作 -> 目标状态；未知动作抛业务异常。 */
    public static String statusOf(String action) {
        String s = action == null ? null : ACTION_TO_STATUS.get(action.trim().toLowerCase());
        if (s == null) {
            throw new BizException("不支持的处置动作: " + action + "（可选 " + ACTION_TO_STATUS.keySet() + "）");
        }
        return s;
    }

    /**
     * 角色 -> 允许的处置动作。**这是权威表**。
     * <p>前端 {@code frontend/src/utils/labels.js} 的 {@code ACTIONS_BY_ROLE} 是同一张表的
     * 展示副本（只决定按钮显不显示）；改这里必须同步改那里，否则会出现「按钮在但请求被拒」
     * 或「按钮没了但接口仍放行」。</p>
     */
    private static final Map<String, Set<String>> ROLE_ACTIONS = Map.of(
            "ADMIN", Set.of("confirm", "research", "dispatch", "handle", "resolve", "misreport"),
            "OPERATOR", Set.of("confirm", "dispatch", "handle", "resolve", "misreport"),
            "ANALYST", Set.of("research", "resolve", "misreport"),
            "MAINTAINER", Set.of("handle", "resolve", "misreport"));

    /**
     * 该角色能否执行该动作。
     * <p>角色为 null / 空 / 未登记一律返回 false —— 这是**失败即拒绝**：认不出来的角色
     * 只能得到空权限，绝不能落到「按管理员放行」那种回退上。</p>
     */
    public static boolean canAct(String role, String action) {
        if (role == null || action == null) {
            return false;
        }
        Set<String> allowed = ROLE_ACTIONS.get(role.trim().toUpperCase());
        return allowed != null && allowed.contains(action.trim().toLowerCase());
    }

    /** 该角色允许的动作集合（用于拒绝时的提示文案）；未知角色返回空集。 */
    public static Set<String> actionsOf(String role) {
        return role == null ? Set.of() : ROLE_ACTIONS.getOrDefault(role.trim().toUpperCase(), Set.of());
    }

    public static boolean isClosed(String status) {
        return status != null && CLOSED.contains(status);
    }
}
