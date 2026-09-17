package com.monitor.alarm.dto;

import lombok.Data;

/**
 * 警情处置请求体。
 *
 * <p><b>为什么没有 {@code operator} 字段</b>：处置人一律取当前登录身份
 * （{@code AlarmService.act} 里的 {@code currentUser}）。早期这里有一个可选的
 * {@code operator}，且服务端让它**优先于**登录用户——于是任何人都能把处置记录
 * 署成别人的名字，而处置留痕正是事后追责要看的那份记录。删掉字段而不是「收下但忽略」，
 * 是为了让「这个值能影响署名」这件事从类型上就不存在。</p>
 *
 * <p>老调用方继续发送该字段不会报错：Spring Boot 默认
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}，多余字段被忽略。
 * {@code tools/acceptance/04-alarm.sh} 特意留着这个诱饵来钉住这条行为。</p>
 */
@Data
public class AlarmActionRequest {

    /** confirm / research / dispatch / handle / resolve / misreport */
    private String action;
    private String comment;
}
