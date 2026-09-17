-- 区分实时上报与历史补报。旧数据按既有行为标记为 REALTIME；新补报由接入服务写 BACKFILL。
ALTER TABLE measurement
    ADD COLUMN ingest_mode VARCHAR(16) NOT NULL DEFAULT 'REALTIME';

ALTER TABLE measurement
    ADD CONSTRAINT ck_measurement_ingest_mode
        CHECK (ingest_mode IN ('REALTIME', 'BACKFILL'));

CREATE INDEX idx_measurement_device_mode_receive
    ON measurement (device_id, ingest_mode, receive_time);
