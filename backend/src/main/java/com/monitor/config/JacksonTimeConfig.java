package com.monitor.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.monitor.common.exception.BizException;
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
 *
 * <p><b>反序列化也必须覆盖</b>，否则输出与输入口径不一致：{@link Times#iso} 写出的是带偏移的
 * {@code 2026-09-14T11:34:45+08:00}，而 Jackson 默认的 {@code LocalDateTime} 反序列化器**只认不带偏移**
 * 的写法，于是「GET 回来的对象原样 PUT 回去」会 400。这不是理论问题：验收套件把设备标 FAULT 时
 * 就是回填 {@code lastReportTime} 触发的，之前用 {@code -o /dev/null} 把 400 吞了，
 * 一路表现成「状态改不动」。</p>
 *
 * <p>容忍范围直接复用 {@link Times#parse}，与 {@code from}/{@code to} 查询参数、{@code IngestService}、
 * {@code MediaService} 的时间口径**保持同一份实现**——这四处以前各写各的，是同一个坑的四个入口。</p>
 */
@Configuration
public class JacksonTimeConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer isoLocalDateTimeCustomizer() {
        return builder -> {
            builder.serializerByType(LocalDateTime.class, LocalDateTimeIsoSerializer.INSTANCE);
            builder.deserializerByType(LocalDateTime.class, LocalDateTimeLenientDeserializer.INSTANCE);
        };
    }

    /** 把裸 {@code LocalDateTime} 按 {@link Times#ZONE} 补上偏移再输出。 */
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

    /**
     * 入参时间宽容解析：ISO8601 带时区 / 不带时区 / {@code yyyy-MM-dd HH:mm:ss} 都收。
     *
     * <p>空串与 {@code null} 一律当「未提供」（返回 null），与 {@link Times#parse} 一致——
     * 前端把空输入框原样提交是常态，不该因此 400。</p>
     */
    static final class LocalDateTimeLenientDeserializer extends JsonDeserializer<LocalDateTime> {

        static final LocalDateTimeLenientDeserializer INSTANCE = new LocalDateTimeLenientDeserializer();

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_NULL) {
                return null;
            }
            String s = p.getValueAsString();
            if (s == null || s.trim().isEmpty()) {
                return null;
            }
            try {
                // 字段名取当前解析位置的名字，让报错指向具体字段（Times.parse 会把它拼进消息）
                return Times.parse(s, p.currentName() == null ? "时间" : p.currentName());
            } catch (BizException e) {
                // 转成 Jackson 自己的格式异常：带上原始写法与字段路径。
                // 注意客户端**看不到**这些细节——GlobalExceptionHandler 对 HttpMessageNotReadableException
                // 统一回「请求参数格式错误」（契约既有文案，不动）。留着是为了排查时能从异常链里读到
                // 是哪个字段、喂了什么值，而不是只看到一个 400。
                throw InvalidFormatException.from(p, e.getMessage(), s, LocalDateTime.class);
            }
        }
    }
}
