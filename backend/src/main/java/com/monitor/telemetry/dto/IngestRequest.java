package com.monitor.telemetry.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.common.exception.BizException;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 接入请求体。契约 §2 的两种形态**都要收**：
 * <ol>
 *   <li>单条标准消息：{@code {"messageId":"...","deviceId":"...","pointCode":"...", ...}}</li>
 *   <li>批量：{@code {"items":[ IngestMessage, ... ]}}</li>
 * </ol>
 *
 * <p>为什么要 {@link #from(JsonNode)} 而不是让 Jackson 直接绑本类：本类的字段只有
 * {@code items}，单条消息会被**静默**绑成 {@code items == null}，服务层于是原样返回
 * {@code accepted=0} —— HTTP 200、一个字都没入库，调用方完全看不出错。契约既然写了
 * 「单条标准消息或 items」，两条路就得真的成立；两者都不像时必须报 400，
 * 不能再有「什么都不做但返回成功」这种结果。</p>
 *
 * <p>（验收套件 02 用的全是 {@code items} 形态，所以这条坑一直没被覆盖到。）</p>
 */
@Data
public class IngestRequest {
    private List<IngestMessage> items;

    /**
     * 与 Spring 默认口径一致（多出来的字段忽略）：设备多发一个字段不该让整条消息被拒。
     * 这里不能借 Spring 容器里那只 ObjectMapper（本类是纯 DTO），故自备一只。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 解析请求体；形态不合法一律 400，绝不返回「空 items」这种假成功。 */
    public static IngestRequest from(JsonNode body) {
        if (body == null || body.isNull()) {
            throw new BizException("请求体不能为空");
        }

        IngestRequest req = new IngestRequest();
        if (body.isArray()) {
            // 容错：直接把数组当作 items。契约没写这种写法，但含义没有歧义，
            // 拒绝它只是让人多绕一步。
            req.setItems(convert(body));
        } else if (body.isObject()) {
            JsonNode items = body.get("items");
            if (items != null && !items.isNull()) {
                if (!items.isArray()) {
                    throw new BizException("items 必须是数组");
                }
                req.setItems(convert(items));
            } else if (looksLikeMessage(body)) {
                List<IngestMessage> single = new ArrayList<>(1);
                single.add(toMessage(body));
                req.setItems(single);
            } else {
                throw new BizException(
                        "请求体既不是标准消息（缺 messageId/deviceId/pointCode），也没有 items 数组");
            }
        } else {
            throw new BizException("请求体必须是 JSON 对象或数组");
        }

        if (req.getItems().isEmpty()) {
            throw new BizException("items 为空，没有可接入的消息");
        }
        return req;
    }

    /** 认一条「像消息」的对象：三个业务键里至少有一个。 */
    private static boolean looksLikeMessage(JsonNode node) {
        return node.hasNonNull("messageId") || node.hasNonNull("deviceId") || node.hasNonNull("pointCode");
    }

    private static List<IngestMessage> convert(JsonNode array) {
        List<IngestMessage> list = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            if (node == null || node.isNull()) {
                // 服务层原本 `if (m == null) continue;` 会把 null 元素悄悄跳过，
                // 同样属于「看着成功、其实没接」——这里直接拒掉
                throw new BizException("items 里存在 null 元素");
            }
            list.add(toMessage(node));
        }
        return list;
    }

    private static IngestMessage toMessage(JsonNode node) {
        if (!node.isObject()) {
            throw new BizException("消息必须是 JSON 对象，实际是: " + node.getNodeType());
        }
        try {
            return MAPPER.convertValue(node, IngestMessage.class);
        } catch (IllegalArgumentException e) {
            // 只取第一行：Jackson 的原始信息后面会附一段 stack 样式的引用链，
            // 甩给调用方（很可能只是设备端写错一个字段类型）没有意义
            String detail = String.valueOf(e.getMessage()).split("\n")[0];
            throw new BizException("消息字段类型不合法: " + detail);
        }
    }
}
