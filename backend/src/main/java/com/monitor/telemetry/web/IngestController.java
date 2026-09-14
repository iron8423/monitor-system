package com.monitor.telemetry.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.monitor.common.Result;
import com.monitor.telemetry.dto.IngestRequest;
import com.monitor.telemetry.dto.IngestResult;
import com.monitor.telemetry.service.IngestService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B1 接入端点。鉴权：A 的 SecurityConfig 已放行 /api/v1/ingest/** 并校验
 * 请求头 X-Ingest-Key（dev 值 dev-ingest-key，读 MONITOR_INGEST_KEY）。
 * 此处只做业务接收，不再重复鉴权。
 *
 * <p>请求体**先按 {@link JsonNode} 收下来再解析**（{@link IngestRequest#from}）：
 * 契约 §2 允许「单条标准消息」与「{@code {"items":[...]}}」两种形态，直接绑
 * {@code IngestRequest} 的话单条会被静默丢成空批量（HTTP 200 + accepted=0）。</p>
 */
@RestController
@RequestMapping("/api/v1/ingest")
public class IngestController {

    private final IngestService service;

    public IngestController(IngestService service) {
        this.service = service;
    }

    @PostMapping("/measurements")
    public Result<IngestResult> ingest(@RequestBody JsonNode body) {
        return Result.ok(service.ingest(IngestRequest.from(body)));
    }
}
