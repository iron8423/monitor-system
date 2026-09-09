package com.monitor.telemetry.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** ingest 响应 data：accepted / rejected / duplicates / results。 */
@Data
public class IngestResult {
    private int accepted = 0;
    private int rejected = 0;
    private int duplicates = 0;
    private List<Item> results = new ArrayList<>();

    @Data
    public static class Item {
        private String pointCode;
        private String collectTime;
        private String status;    // OK / DUPLICATE / REJECTED
        private String quality;
    }
}
