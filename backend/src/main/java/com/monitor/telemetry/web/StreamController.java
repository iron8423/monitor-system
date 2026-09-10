package com.monitor.telemetry.web;

import com.monitor.common.sse.SseBroadcaster;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 实时推送（《B侧接口契约_M0》§6）。
 *
 * <p>{@code EventSource} 带不了请求头，故本路径的 JWT 从 query 取
 * （{@code GET /api/v1/stream?token=<JWT>}，见 {@code JwtAuthFilter} D8）；
 * 仍是鉴权接口，无 token / token 非法一律 401。</p>
 *
 * <p>事件：{@code connected}（订阅建立）、{@code measurement}（新测值）、{@code alarm}（警情产生/状态变更）。</p>
 */
@RestController
@RequestMapping("/api/v1/stream")
@RequiredArgsConstructor
@SuppressWarnings("null")
public class StreamController {

    private final SseBroadcaster broadcaster;

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.subscribe();
    }
}
