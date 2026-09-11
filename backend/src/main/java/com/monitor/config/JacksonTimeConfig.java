package com.monitor.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.monitor.common.util.Times;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 时间序列化统一口径：所有裸 {@link LocalDateTime} 一律出 ISO8601 带时区（契约 §0）。
 *
 * <p>为什么必须有这个 Bean：{@code spring.jackson.date-format} 只作用于 {@code java.util.Date}，
 * 对 JSR-310 类型**完全无效**。默认的 {@code JavaTimeModule} 会把 {@code LocalDateTime} 写成
 * {@code 2026-08-27T15:05:00}——**不带偏移**，前端无从判断这是哪个时区的时间。</p>
 *
 * <p>已走 {@code Times.iso} 的字段（{@code AlarmService}/ {@code DeviceController} 里的 {@code String}）
 * 不受影响，它们本来就是对的；这里补的是档案 CRUD 把实体原样返回的那批
 * （{@code BaseEntity.createdAt/updatedAt}，见 {@code BaseCrudController}）。</p>
 */
@Configuration
public class JacksonTimeConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer isoLocalDateTimeCustomizer() {
        return builder -> builder.serializerByType(LocalDateTime.class, LocalDateTimeIsoSerializer.INSTANCE);
    }

    /** 把裸 {@code LocalDateTime} 按 {@link Times#ZONE} 补上偏移再输出。只覆盖序列化，反序列化不变。 */
    static final class LocalDateTimeIsoSerializer extends JsonSerializer<LocalDateTime> {

        static final LocalDateTimeIsoSerializer INSTANCE = new LocalDateTimeIsoSerializer();

        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (value == null) {
                gen.writeNull();
            } else {
                gen.writeString(Times.iso(value));
            }
        }
    }
}
