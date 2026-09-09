package com.monitor.telemetry.dto;

import lombok.Data;
import java.util.List;

/** 批量上报：{ "items": [ IngestMessage, ... ] }；单条请求也按此结构。 */
@Data
public class IngestRequest {
    private List<IngestMessage> items;
}
