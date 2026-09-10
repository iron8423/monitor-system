package com.monitor.alarm;

import com.monitor.common.exception.BizException;

import java.util.LinkedHashMap;
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

    /** 系统自动动作（非人工处置）。 */
    public static final String ACTION_TRIGGER = "trigger";
    public static final String ACTION_RECOVER = "recover";
    public static final String SYSTEM = "system";

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

    public static boolean isClosed(String status) {
        return status != null && CLOSED.contains(status);
    }
}
