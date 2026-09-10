package com.monitor.alarm.web;

import com.monitor.alarm.dto.AlarmActionRequest;
import com.monitor.alarm.dto.AlarmDetailVO;
import com.monitor.alarm.dto.AlarmVO;
import com.monitor.alarm.service.AlarmService;
import com.monitor.auth.security.SecurityUser;
import com.monitor.common.PageResult;
import com.monitor.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 警情查询与处置（《B侧接口契约_M0》§4）。
 * 处置动作对全部已登录角色开放，界面上按角色显隐由前端控制。
 */
@RestController
@RequestMapping("/api/v1/alarms")
@RequiredArgsConstructor
@SuppressWarnings("null")
public class AlarmController {

    private final AlarmService alarmService;

    @GetMapping
    public Result<PageResult<AlarmVO>> list(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "20") long pageSize,
                                            @RequestParam(required = false) String level,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Long pointId,
                                            @RequestParam(required = false) Long deviceId,
                                            @RequestParam(required = false) String alarmType,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        return Result.ok(alarmService.list(level, status, pointId, deviceId, alarmType, from, to, pageNum, pageSize));
    }

    @GetMapping("/{id}")
    public Result<AlarmDetailVO> detail(@PathVariable Long id) {
        return Result.ok(alarmService.detail(id));
    }

    @PostMapping("/{id}/actions")
    public Result<AlarmDetailVO> act(@PathVariable Long id,
                                     @RequestBody AlarmActionRequest req,
                                     @AuthenticationPrincipal SecurityUser currentUser) {
        return Result.ok(alarmService.act(id, req, currentUser == null ? null : currentUser.getUsername()));
    }
}
