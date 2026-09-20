package com.monitor.telemetry.web;

import com.monitor.audit.annotation.AuditAction;
import com.monitor.auth.security.SecurityUser;
import com.monitor.common.Result;
import com.monitor.telemetry.dto.MeasurementBaselineRequest;
import com.monitor.telemetry.dto.MeasurementBaselineVO;
import com.monitor.telemetry.service.MeasurementBaselineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 测量基准（复查清单 P1-4）：换反射器 / 重新安装 / 设备归零之后，累计形变会从 0 重来，
 * 而曲线会把两段接在一起。这个端点让"换过基准"这件事**留下痕迹**：
 * 读当前基准与历史，写（登记）一次变更。
 *
 * <p>权限口径与标定一致（{@code ADMIN} 或 {@code MAINTAINER}）：它描述的是现场发生过的事，
 * 由能去现场、能标定的人登记；值班员与研判员只读。</p>
 *
 * <p>登记会写审计（{@code @AuditAction}），操作人取登录身份——与处置留痕同一条规矩。</p>
 */
@RestController
@RequestMapping("/api/v1/points/{pointId}/baseline")
@RequiredArgsConstructor
public class MeasurementBaselineController {

    private final MeasurementBaselineService baselineService;

    /** 当前生效的基准；从未登记过时 {@code data} 为 null（不是 404——"没有基准"是正常状态）。 */
    @GetMapping
    public Result<MeasurementBaselineVO> current(@PathVariable Long pointId) {
        return Result.ok(baselineService.current(pointId));
    }

    /** 变更历史（新的在前）。 */
    @GetMapping("/history")
    public Result<List<MeasurementBaselineVO>> history(@PathVariable Long pointId) {
        return Result.ok(baselineService.history(pointId));
    }

    /** 原因白名单（供界面做下拉，避免前端抄一份中文标签）。 */
    @GetMapping("/reasons")
    public Result<Map<String, String>> reasons() {
        return Result.ok(MeasurementBaselineService.reasons());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MAINTAINER')")
    @AuditAction(action = "登记测量基准")
    public Result<MeasurementBaselineVO> create(@PathVariable Long pointId,
                                                @Valid @RequestBody MeasurementBaselineRequest request,
                                                @AuthenticationPrincipal SecurityUser currentUser) {
        String operator = currentUser == null ? null : currentUser.getUsername();
        return Result.ok(baselineService.create(pointId, request, operator));
    }
}
