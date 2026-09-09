package com.monitor.telemetry.web;

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
 */
@RestController
@RequestMapping("/api/v1/ingest")
public class IngestController {

    private final IngestService service;

    public IngestController(IngestService service) {
        this.service = service;
    }

    @PostMapping("/measurements")
    public Result<IngestResult> ingest(@RequestBody IngestRequest req) {
        return Result.ok(service.ingest(req));
    }
}
