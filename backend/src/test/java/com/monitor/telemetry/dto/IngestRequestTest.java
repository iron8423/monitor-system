package com.monitor.telemetry.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IngestRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultsToRealtimeForExistingClients() throws Exception {
        IngestRequest request = IngestRequest.from(mapper.readTree("""
                {"items":[{"messageId":"m1","deviceId":"d1","pointCode":"p1"}]}
                """));

        assertEquals(IngestMode.REALTIME, request.getIngestMode());
    }

    @Test
    void parsesBackfillCaseInsensitively() throws Exception {
        IngestRequest request = IngestRequest.from(mapper.readTree("""
                {"ingestMode":"backfill","items":[{"messageId":"m1","deviceId":"d1","pointCode":"p1"}]}
                """));

        assertEquals(IngestMode.BACKFILL, request.getIngestMode());
    }

    @Test
    void rejectsUnknownMode() throws Exception {
        assertThrows(BizException.class, () -> IngestRequest.from(mapper.readTree("""
                {"ingestMode":"REPLAY","items":[{"messageId":"m1","deviceId":"d1","pointCode":"p1"}]}
                """)));
    }
}
