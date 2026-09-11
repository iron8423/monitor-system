package com.monitor.alarm.web;

import com.monitor.alarm.dto.AlarmRuleRequest;
import com.monitor.alarm.dto.AlarmRuleVO;
import com.monitor.alarm.service.AlarmRuleService;
import com.monitor.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 告警规则 CRUD（《B侧接口契约_M0》§4）。读开放（与 A 的档案 CRUD 一致），写仅 ADMIN。
 */
@RestController
@RequestMapping("/api/v1/alarm-rules")
@RequiredArgsConstructor
@SuppressWarnings("null")
public class AlarmRuleController {

    private final AlarmRuleService ruleService;

    @GetMapping
    public Result<List<AlarmRuleVO>> list() {
        return Result.ok(ruleService.list());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<AlarmRuleVO> create(@RequestBody AlarmRuleRequest req) {
        return Result.ok(ruleService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<AlarmRuleVO> update(@PathVariable Long id, @RequestBody AlarmRuleRequest req) {
        return Result.ok(ruleService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        ruleService.delete(id);
        return Result.ok();
    }
}
