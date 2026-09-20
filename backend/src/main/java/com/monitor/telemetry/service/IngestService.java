package com.monitor.telemetry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.alarm.service.AlarmEngine;
import com.monitor.asset.entity.Device;
import com.monitor.asset.entity.DevicePoint;
import com.monitor.asset.RadarCoveragePolicy;
import com.monitor.asset.mapper.DevicePointMapper;
import com.monitor.asset.mapper.DeviceMapper;
import com.monitor.common.exception.BizException;
import com.monitor.common.sse.SseBroadcaster;
import com.monitor.common.util.Times;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.entity.Metric;
import com.monitor.project.mapper.MetricMapper;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.telemetry.IngestTimePolicy;
import com.monitor.telemetry.dto.IngestMessage;
import com.monitor.telemetry.dto.IngestMode;
import com.monitor.telemetry.dto.IngestRequest;
import com.monitor.scope.service.DataScopeService;
import com.monitor.telemetry.dto.IngestResult;
import com.monitor.telemetry.dto.MeasurementEvent;
import com.monitor.telemetry.entity.Measurement;
import com.monitor.telemetry.mapper.MeasurementMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B1：接入。规则（对齐 A 冻结口径）：
 *  - 幂等：device_id + message_id（重复整条去重）；
 *  - 落库：一条含 N 测项 -> 拆 N 行 measurement，共用 message_id；
 *  - 附加字段 position/signal/state -> attributes(JSON <=1024)；
 *  - 质量：quality 缺省按 信号/state/metrics 推导；
 *  - 时间：collectTime 必填且必须可解析（非法 -> 整条 REJECTED）；receiveTime 缺省取当前时间。
 *  - 模式：REALTIME 才推进心跳/SSE/当前告警；BACKFILL 只落历史库。
 */
@Service
@Slf4j
public class IngestService {

    private final MeasurementMapper mapper;
    private final MonitorPointMapper pointMapper;
    private final DeviceMapper deviceMapper;
    private final DevicePointMapper devicePointMapper;
    private final MetricMapper metricMapper;
    private final AlarmEngine alarmEngine;
    private final SseBroadcaster broadcaster;
    private final DataScopeService dataScope;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Set<String> QUALITY_VALUES = Set.of("RAW", "VALID", "SUSPECT", "FAULT");
    private static final Set<String> STATE_VALUES = Set.of("normal", "disappeared", "suspicious");

    /**
     * 开启后执行生产契约：版本、序列号、测项档案、设备-测点绑定和质量枚举必须一致。
     * 开发环境默认关闭，避免旧的无版本演示报文突然失效；生产编排显式打开。
     */
    @Value("${monitor.ingest.strict-contract:false}")
    private boolean strictContract;

    @Value("${monitor.ingest.max-batch-size:2000}")
    private int maxBatchSize;

    /**
     * 采集时间超前平台时间的容忍秒数（清单第 10 条）。口径见 {@link IngestTimePolicy}。
     *
     * <p>这个闸门刻意**不**放进 {@code contractError}：那里第一句是
     * {@code if (!strictContract) return null;}，而全部验收套件与开发环境都是
     * {@code strict-contract=false}——放进去等于它在这些环境里根本不存在。
     * 这是正确性不变式，不是生产契约的附加要求。</p>
     */
    @Value("${monitor.ingest.max-collect-ahead-seconds:300}")
    private int maxCollectAheadSeconds;

    /** 接收时间超前平台时间的容忍秒数；超过则钳制（见 {@link IngestTimePolicy}）。 */
    @Value("${monitor.ingest.max-receive-ahead-seconds:300}")
    private int maxReceiveAheadSeconds;

    public IngestService(MeasurementMapper mapper, MonitorPointMapper pointMapper, DeviceMapper deviceMapper,
                         DevicePointMapper devicePointMapper, MetricMapper metricMapper,
                         AlarmEngine alarmEngine, SseBroadcaster broadcaster, DataScopeService dataScope) {
        this.mapper = mapper;
        this.pointMapper = pointMapper;
        this.deviceMapper = deviceMapper;
        this.devicePointMapper = devicePointMapper;
        this.metricMapper = metricMapper;
        this.alarmEngine = alarmEngine;
        this.broadcaster = broadcaster;
        this.dataScope = dataScope;
    }

    @Transactional
    public IngestResult ingest(IngestRequest req) {
        IngestResult res = new IngestResult();
        IngestMode ingestMode = req == null || req.getIngestMode() == null
                ? IngestMode.REALTIME : req.getIngestMode();
        res.setIngestMode(ingestMode.name());
        if (req == null || req.getItems() == null) {
            return res;
        }
        if (req.getItems().size() > maxBatchSize) {
            throw new BizException(400, "单批消息数不能超过 " + maxBatchSize);
        }
        // 一批报文一次性预取档案。1000 点同刻上报不能退化成「每条查点 + 查设备 +
        // 查测项 + 查绑定」的 N×4 查询风暴。
        Set<String> pointCodes = req.getItems().stream().filter(v -> v != null && notBlank(v.getPointCode()))
                .map(IngestMessage::getPointCode).collect(Collectors.toSet());
        Set<String> deviceCodes = req.getItems().stream().filter(v -> v != null && notBlank(v.getDeviceId()))
                .map(IngestMessage::getDeviceId).collect(Collectors.toSet());
        Set<String> messageIds = req.getItems().stream().filter(v -> v != null && notBlank(v.getMessageId()))
                .map(IngestMessage::getMessageId).collect(Collectors.toSet());
        List<MonitorPoint> pointRows = pointCodes.isEmpty() ? Collections.emptyList()
                : pointMapper.selectList(new LambdaQueryWrapper<MonitorPoint>().in(MonitorPoint::getCode, pointCodes));
        List<Device> deviceRows = deviceCodes.isEmpty() ? Collections.emptyList()
                : deviceMapper.selectList(new LambdaQueryWrapper<Device>().in(Device::getCode, deviceCodes));
        Map<String, MonitorPoint> points = pointRows.stream()
                .collect(Collectors.toMap(MonitorPoint::getCode, Function.identity(), (a, b) -> a));
        Map<String, Device> devices = deviceRows.stream()
                .collect(Collectors.toMap(Device::getCode, Function.identity(), (a, b) -> a));
        Set<Long> pointIds = pointRows.stream().map(MonitorPoint::getId).collect(Collectors.toSet());
        Set<Long> deviceIds = deviceRows.stream().map(Device::getId).collect(Collectors.toSet());
        List<Metric> metricRows = pointIds.isEmpty() ? Collections.emptyList()
                : metricMapper.selectList(new LambdaQueryWrapper<Metric>().in(Metric::getPointId, pointIds));
        Map<Long, Map<String, Metric>> metricDefinitions = metricRows.stream().collect(Collectors.groupingBy(
                Metric::getPointId,
                Collectors.toMap(Metric::getCode, Function.identity(), (a, b) -> a)));
        List<DevicePoint> bindingRows = !strictContract || pointIds.isEmpty() || deviceIds.isEmpty()
                ? Collections.emptyList()
                : devicePointMapper.selectList(new LambdaQueryWrapper<DevicePoint>()
                        .in(DevicePoint::getPointId, pointIds)
                        .in(DevicePoint::getDeviceId, deviceIds));
        Map<String, DevicePoint> bindings = bindingRows.stream()
                .collect(Collectors.toMap(v -> v.getDeviceId() + ":" + v.getPointId(), Function.identity(), (a, b) -> a));
        List<Measurement> existingRows = messageIds.isEmpty() ? Collections.emptyList()
                : mapper.selectList(new LambdaQueryWrapper<Measurement>().in(Measurement::getMessageId, messageIds));
        Set<String> existingMessages = existingRows.stream()
                .map(v -> messageKey(v.getDeviceId(), v.getMessageId()))
                .collect(Collectors.toCollection(HashSet::new));
        Map<Long, LocalDateTime> latestReportByDevice = new HashMap<>();
        // 整批共用一个「现在」。两个理由：2000 条不该调 2000 次 now()；
        // 更要紧的是**同一批的每一条必须对着同一个时钟判**，否则边界那条的结论
        // 取决于这一批处理了多久——同一批报文重发一次可能得到不同的结果。
        LocalDateTime batchNow = LocalDateTime.now();
        for (IngestMessage m : req.getItems()) {
            if (m == null) continue;
            // collectTime 是设备侧时间，缺失/格式非法一律拒收（见 parseCollectTime 的说明）
            LocalDateTime collectAt = parseCollectTime(m.getCollectTime());
            String basicError = basicError(m, collectAt, batchNow);
            if (basicError != null) {
                reject(res, m, basicError, null);
                continue;
            }
            // 点号/设备都必须在档案中存在（契约 §2：deviceId、pointCode 不存在 -> REJECTED）
            MonitorPoint point = points.get(m.getPointCode());
            Device device = devices.get(m.getDeviceId());
            if (point == null) {
                reject(res, m, "UNKNOWN_POINT", null);
                continue;
            }
            if (device == null) {
                reject(res, m, "UNKNOWN_DEVICE", null);
                continue;
            }
            Long pointId = point.getId();
            Map<String, Metric> definitions = metricDefinitions.getOrDefault(pointId, Collections.emptyMap());
            String contractError = contractError(m, point, device, definitions, bindings);
            if (contractError != null) {
                reject(res, m, contractError, null);
                continue;
            }
            LocalDateTime receiveAt = parseReceiveTime(m.getReceiveTime());
            if (strictContract && notBlank(m.getReceiveTime()) && receiveAt == null) {
                reject(res, m, "INVALID_RECEIVE_TIME", null);
                continue;
            }
            if (receiveAt == null) receiveAt = LocalDateTime.now();
            // 清单第 10 条的另一半，而且**这才是要紧的那一半**。
            //
            // 清单把「影响在线状态」归给 collectTime，其实不是：设备心跳读的是 receiveAt
            // （见下面 latestReportByDevice），而 DeviceStatusPolicy 判的是
            // lastReportTime.isAfter(now - 5min)——一个未来的 receiveTime **永远满足**它，
            // 那台设备从此再也不会离线。
            //
            // 钳制而不是拒收：receiveTime 是**平台自己的字段**，契约里写明「平台接收时间」，
            // 且是唯一允许缺省取当前时间的字段。为了一个头部字段写错就丢掉一条真实测量，
            // 丢掉的是平台本身就是权威的那份数据。
            //
            // **位置是承重的**：钳制必须在上面那个严格模式判定**之后**。挪到前面的话，
            // 契约负向用例 invalid-receive-time（发 "receiveTime":"tomorrow"，期望
            // REJECTED:INVALID_RECEIVE_TIME）会变成「收下并静默改写时间戳」——该用例转红。
            // 钳制只作用于**成功解析出来**的值。
            LocalDateTime clamped = IngestTimePolicy.clampReceiveTime(receiveAt, batchNow, maxReceiveAheadSeconds);
            if (!clamped.equals(receiveAt)) {
                log.info("接收时间超前平台时间，已钳到当前 deviceId={} pointCode={} messageId={} reported={}",
                        m.getDeviceId(), m.getPointCode(), m.getMessageId(), Times.iso(receiveAt));
                receiveAt = clamped;
            }
            String attributes = toAttributes(m);
            if (attributes == null) {
                reject(res, m, "ATTRIBUTES_TOO_LARGE", null);
                continue;
            }
            // 幂等：device_id + message_id 已存在 -> 整条跳过
            String idempotencyKey = messageKey(m.getDeviceId(), m.getMessageId());
            if (existingMessages.contains(idempotencyKey)) {
                res.setDuplicates(res.getDuplicates() + 1);
                res.getResults().add(item(m, "DUPLICATE", null, null));
                continue;
            }
            String q = (m.getQuality() != null && !m.getQuality().isEmpty())
                    ? m.getQuality().trim().toUpperCase() : deriveQuality(m);
            // 落库要能扛住**并发**重发。上面按 messageIds 的预取只挡得住顺序重发：
            // 两个并发请求（网关重发、断点续传重试）会同时通过预取、各自 INSERT，
            // 其中一方撞上 uk_measurement_device_msg。撞了只该把这一条判成重复，
            // 不能连累同批其它完全合法的测点——那正是本条要修的（见 insertRowsGuarded）。
            int rows = insertRowsGuarded(m, m.getDeviceId(), m.getMessageId(), pointId, definitions,
                    q, collectAt, receiveAt, attributes, ingestMode);
            if (rows == DUPLICATE_MESSAGE) {
                // 结果条目与上面「预取命中」那条路径逐字一致（quality 也是 null）：
                // 同一件事不该因为「被预取挡下的」还是「被唯一索引挡下的」而给出不同的响应。
                res.setDuplicates(res.getDuplicates() + 1);
                res.getResults().add(item(m, "DUPLICATE", null, null));
                existingMessages.add(idempotencyKey);
                continue;
            }
            if (rows == 0) {  // 无有效测项 -> 视为无效
                reject(res, m, "EMPTY_METRICS", q);
                continue;
            }
            if (ingestMode == IngestMode.REALTIME) {
                // 实时推送必须排在告警评估之前：提交后先有测值、再有警情。
                broadcaster.broadcastScoped(SseBroadcaster.EVENT_MEASUREMENT,
                        MeasurementEvent.of(pointId, m.getPointCode(), Times.iso(collectAt), m.getMetrics(), q),
                        () -> dataScope.projectIdsOfPoint(pointId));

                // 仅实时消息推进设备在线心跳。补报不能把离线设备“救活”。
                latestReportByDevice.merge(device.getId(), receiveAt,
                        (a, b) -> a.isAfter(b) ? a : b);

                // 仅实时消息触发、升级或解除当前告警。
                //
                // state=disappeared（目标失联）的观测不参与评估：那条消息带的是目标丢失前后的
                // 旧值或 0，既不构成一次新观测，更不能拿来**解除**已经存在的超限警情
                // ——「目标不见了」被读成「位移回到阈值内」正是要避免的那件事。
                //
                // 闸设在这里而不是 deriveQuality 里，有两个理由：
                //   ① 上面 q 的计算让设备**显式**声明的 quality 优先于推导，
                //      所以把推导标成 SUSPECT 挡不住 quality:"VALID" + state:"disappeared"；
                //   ② 标 SUSPECT 会连带被 DataQualityPolicy.isBadQuality 计入坏样本，
                //      持续报失联的设备会冒出一条语义错误的「数据质量异常」告警。
                //
                // 该行照常落库、照常进 SSE、照常推进心跳：失联本身是有效的链路观测，
                // 只是不能充当测值（attributes 里存着 state，界面渲染成「目标失联」）。
                if (!"disappeared".equalsIgnoreCase(m.getState())) {
                    for (Map.Entry<String, Double> e : m.getMetrics().entrySet()) {
                        if (e.getValue() != null) {
                            alarmEngine.evaluate(pointId, e.getKey(), e.getValue(), q);
                        }
                    }
                }
            }
            res.setAccepted(res.getAccepted() + rows);   // accepted = 落库行数
            res.getResults().add(item(m, "OK", q, null));
            existingMessages.add(idempotencyKey);
        }
        // 同一批一台雷达可能上报 100 个点，只回写一次设备心跳，避免无意义的热点行更新。
        for (Device device : devices.values()) {
            LocalDateTime reportAt = latestReportByDevice.get(device.getId());
            if (reportAt == null) continue;
            device.setLastReportTime(reportAt);
            deviceMapper.updateById(device);
        }
        return res;
    }

    /** {@link #insertRowsGuarded} 的返回值：这条消息已经落过库，是重复上报，不是错误。 */
    private static final int DUPLICATE_MESSAGE = -1;

    /**
     * 插入一条消息的全部测项行，并把「并发撞上幂等键」就地消化掉。
     *
     * <p><b>为什么需要保存点</b>：{@code ingest} 整批共用一个事务。PG 上一旦有语句报错，
     * 事务立刻进入 aborted 状态、后续语句全部被拒——不设保存点就只剩「整批回滚」一条路，
     * 同批里其它完全合法的测点会跟着一起丢。保存点让这次失败只回退**这一条消息**的插入。
     * 这是清单第 11 条要修的核心：一条重复上报不该让一批无关数据陪葬。</p>
     *
     * <p><b>为什么回退之后必须回查幂等键</b>：撞唯一键有且只有两种解释，必须分开——
     * ① 别人已经把这条消息落库了（真重复）-> 记 DUPLICATE；
     * ② 库里没有（例如我方是死锁的牺牲者，而对方已回滚）-> 这次失败是暂时的，重试一次。
     * 分不清就会把 ② 报成重复，那等于**把一条真实测值悄悄丢掉**——比报 500 更坏，
     * 因为 500 至少会让网关重发，而「重复」会让它认为已经送到了。</p>
     *
     * <p>回查用的是 READ COMMITTED 下的新快照，所以对象事务一旦提交就能看见；
     * 唯一键冲突本身就会阻塞到对方提交或回滚为止，不会出现「它还没提交我就报错」。</p>
     *
     * @return 落库行数；{@link #DUPLICATE_MESSAGE} 表示这条消息已存在
     */
    private int insertRowsGuarded(IngestMessage m, String deviceId, String messageId, Long pointId,
                                  Map<String, Metric> definitions, String q, LocalDateTime collectAt,
                                  LocalDateTime receiveAt, String attributes, IngestMode ingestMode) {
        for (int attempt = 0; ; attempt++) {
            Object savepoint = createSavepoint();
            try {
                int rows = insertRows(m, pointId, definitions, q, collectAt, receiveAt, attributes, ingestMode);
                releaseSavepoint(savepoint);
                return rows;
            } catch (DuplicateKeyException | CannotAcquireLockException e) {
                rollbackToSavepoint(savepoint);
                if (mapper.countByMessageId(deviceId, messageId) > 0) {
                    // 走到这里说明**真的**发生了并发撞键：预取（按 messageIds 一次查完）没挡住，
                    // 是唯一索引挡住的。这与「预取命中」是两件事——预取命中是顺序重发（常见、无害），
                    // 这里是并发重发（网关重试/断点续传撞在一起）。记一条 info 是为了让它可观测：
                    // 没有这行日志时，「并发撞键了」与「请求恰好串行、预取挡住了」在外部**完全同形**，
                    // 验收套件无法证明自己真的走到了保存点这条路。
                    log.info("接入并发重复上报：唯一索引拦下后回查命中，按重复处理 deviceId={} messageId={}",
                            deviceId, messageId);
                    return DUPLICATE_MESSAGE;
                }
                // 库里确实没有 -> 不是重复。重试一次；再失败就原样抛出去让调用方拿到 500：
                // 宁可报一个「不知道落没落」的未知错误，也不能把没落库的数据说成「重复」。
                if (attempt >= 1) {
                    throw e;
                }
                log.warn("接入落库撞锁但幂等键不存在，重试一次 deviceId={} messageId={}", deviceId, messageId, e);
            }
        }
    }

    /** 插一条消息的测项行，返回落库行数。测项为 null 或全为空值时返回 0（由调用方判 EMPTY_METRICS）。 */
    private int insertRows(IngestMessage m, Long pointId, Map<String, Metric> definitions, String q,
                           LocalDateTime collectAt, LocalDateTime receiveAt, String attributes,
                           IngestMode ingestMode) {
        if (m.getMetrics() == null) {
            return 0;
        }
        int rows = 0;
        for (Map.Entry<String, Double> e : m.getMetrics().entrySet()) {
            if (e.getValue() == null) continue;
            mapper.insert(buildRow(m, pointId, definitions.get(e.getKey()), e.getKey(),
                    e.getValue(), q, collectAt, receiveAt, attributes, ingestMode));
            rows++;
        }
        return rows;
    }

    // 保存点三件套。没有活动事务时（单测直接调 service，不经过 Spring 代理）返回 null、
    // 三个方法全部退化为空操作——否则纯 Mockito 单测会因为 NoTransactionException 挂掉，
    // 而单测要验的恰恰是「撞了唯一键之后返回什么」，不该被事务基础设施挡住。

    private Object createSavepoint() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                ? TransactionAspectSupport.currentTransactionStatus().createSavepoint() : null;
    }

    private void rollbackToSavepoint(Object savepoint) {
        if (savepoint != null) {
            TransactionAspectSupport.currentTransactionStatus().rollbackToSavepoint(savepoint);
        }
    }

    private void releaseSavepoint(Object savepoint) {
        if (savepoint != null) {
            TransactionAspectSupport.currentTransactionStatus().releaseSavepoint(savepoint);
        }
    }

    /**
     * 与配置无关的基本校验（缺失、超长、格式）。**顺序敏感**：
     * {@code collectAt == null} 与 {@code COLLECT_TIME_IN_FUTURE} 是两条不同的原因码，
     * 前者必须排在最前，否则「没带 collectTime」会先被算成「时间在未来」而报错码。
     */
    private String basicError(IngestMessage m, LocalDateTime collectAt, LocalDateTime now) {
        if (!notBlank(m.getMessageId())) return "MISSING_MESSAGE_ID";
        if (!notBlank(m.getDeviceId())) return "MISSING_DEVICE_ID";
        if (!notBlank(m.getPointCode())) return "MISSING_POINT_CODE";
        if (m.getMessageId().length() > 64) return "MESSAGE_ID_TOO_LONG";
        if (m.getDeviceId().length() > 64) return "DEVICE_ID_TOO_LONG";
        if (m.getPointCode().length() > 64) return "POINT_CODE_TOO_LONG";
        if (collectAt == null) return "INVALID_COLLECT_TIME";
        // 清单第 10 条：设备的采集时间不能跑到平台前面去。
        //
        // 为什么要拒收而不是收下：一个 2099 年的 collectTime 会**永久**钉住该测点的「当前值」
        // ——MeasurementMapper 的排序是 collect_time DESC，它无条件胜出。被钉住的包括
        // /points/{id}/latest、项目快照与概览 KPI，以及前端 SSE 的单调合并
        // （丢弃 collectTime 不大于当前最大值的事件），于是之后所有正常上报在界面上**全部被丢弃**，
        // 不刷新页面回不来。
        //
        // 为什么算「设备的错」而不是平台的错：collectTime 是设备侧时间，契约里它必填且
        // 必须可解析——设备报了一个平台还没活到的时间，这条报文本身不可信。
        // 拒收会被计入 rejected 并逐条给出原因码，网关据此能定位到那台设备。
        if (IngestTimePolicy.tooFarAhead(collectAt, now, maxCollectAheadSeconds)) {
            return "COLLECT_TIME_IN_FUTURE";
        }
        if (m.getMetrics() == null || m.getMetrics().isEmpty()) return "EMPTY_METRICS";
        if (m.getMetrics().entrySet().stream().anyMatch(e -> !notBlank(e.getKey()) || e.getValue() == null
                || !Double.isFinite(e.getValue()))) return "INVALID_METRIC_VALUE";
        return null;
    }

    private String contractError(IngestMessage m, MonitorPoint point, Device device,
                                 Map<String, Metric> definitions, Map<String, DevicePoint> bindings) {
        if (!strictContract) return null;
        if (!"1.0".equals(m.getSchemaVersion())) return "UNSUPPORTED_SCHEMA_VERSION";
        if (m.getSequence() == null || m.getSequence() < 0) return "INVALID_SEQUENCE";
        if (Boolean.FALSE.equals(point.getEnabled())) return "POINT_DISABLED";
        String quality = m.getQuality() == null ? null : m.getQuality().trim().toUpperCase();
        if (quality != null && !QUALITY_VALUES.contains(quality)) return "UNKNOWN_QUALITY";
        if (m.getSignal() != null && (!Double.isFinite(m.getSignal()) || m.getSignal() < 0 || m.getSignal() > 1)) {
            return "INVALID_SIGNAL";
        }
        if (notBlank(m.getState()) && !STATE_VALUES.contains(m.getState().trim().toLowerCase())) {
            return "UNKNOWN_STATE";
        }
        if (m.getPosition() != null) {
            Object angle = m.getPosition().get("angleDeg");
            Object distance = m.getPosition().get("distanceM");
            if ((angle != null && !(angle instanceof Number))
                    || (distance != null && (!(distance instanceof Number) || ((Number) distance).doubleValue() < 0))) {
                return "INVALID_POSITION";
            }
        }
        if (!definitions.keySet().containsAll(m.getMetrics().keySet())) return "UNKNOWN_METRIC";
        DevicePoint binding = bindings.get(device.getId() + ":" + point.getId());
        if (binding == null) return "DEVICE_POINT_NOT_BOUND";
        if (!RadarCoveragePolicy.isProductionReady(binding, LocalDateTime.now())) {
            return "DEVICE_POINT_NOT_CALIBRATED";
        }
        if (m.getPosition() != null
                && !RadarCoveragePolicy.matchesReportedPosition(
                device, binding,
                (Number) m.getPosition().get("angleDeg"),
                (Number) m.getPosition().get("distanceM"))) {
            // 这条**只拒收、不回写**档案状态（清单第 09 条的另一半刻意不做在这里）。
            //
            // 那个决定值得写下来，因为「顺手把标定打成失效」看起来更彻底：
            // 接入批次是无状态、幂等、被契约要求**可重试**的，而失效是一次档案写操作。
            // 把档案变更藏在一个拒收理由后面，会让同一个请求第二次返回不同答案
            // （第一次 REJECTED 并改了档案，第二次理由就变了），也会让
            // contract-negative-cases.json 里那条 position-calibration-mismatch
            // 变成「跑一次变一次」的顺序相关用例。
            //
            // 但运维确实需要看见它：没有这行日志时，唯一的症状是该雷达的生产报文被持续拒收
            // （数据静默断流），而界面上那条标定还是「有效」——排查要从解析逐条拒收原因开始。
            log.warn("上报位置与标定不符，该雷达可能已被移动 deviceId={} pointCode={} messageId={} "
                            + "reportedAngle={} reportedDistance={} calibratedAzimuth={} calibratedSlantRange={} heading={}",
                    m.getDeviceId(), m.getPointCode(), m.getMessageId(),
                    m.getPosition().get("angleDeg"), m.getPosition().get("distanceM"),
                    binding.getAzimuthDegrees(), binding.getSlantRangeM(), device.getHeadingDegrees());
            return "POSITION_CALIBRATION_MISMATCH";
        }
        return null;
    }

    private String deriveQuality(IngestMessage m) {
        if (m.getMetrics() == null || m.getMetrics().isEmpty()) return "SUSPECT";
        if (m.getSignal() != null && m.getSignal() < 0.3) return "SUSPECT";
        if ("suspicious".equalsIgnoreCase(m.getState())) return "SUSPECT";
        for (Double v : m.getMetrics().values()) {
            if (v == null || v.isNaN() || v.isInfinite()) return "FAULT";
        }
        return "VALID";
    }

    private Measurement buildRow(IngestMessage m, Long pointId, Metric definition, String metricCode,
                                 Double value, String quality, LocalDateTime collectAt,
                                 LocalDateTime receiveAt, String attributes, IngestMode ingestMode) {
        Measurement row = new Measurement();
        row.setMessageId(m.getMessageId());
        row.setDeviceId(m.getDeviceId());
        row.setPointId(pointId);
        row.setPointCode(m.getPointCode());
        row.setMetricId(definition == null ? null : definition.getId());
        row.setMetricCode(metricCode);
        row.setSeq(m.getSequence());
        row.setSchemaVersion(m.getSchemaVersion());
        row.setIngestMode(ingestMode.name());
        row.setCollectTime(collectAt);
        row.setReceiveTime(receiveAt);
        row.setMeasureValue(value);
        row.setUnit(definition == null ? null : definition.getUnit());
        row.setQuality(quality);
        row.setAttributes(attributes);
        row.setRawRef(m.getMessageId());
        return row;
    }

    private String toAttributes(IngestMessage m) {
        Map<String, Object> att = new HashMap<>();
        if (m.getPosition() != null) att.put("position", m.getPosition());
        if (m.getSignal() != null) att.put("signal", m.getSignal());
        if (m.getState() != null) att.put("state", m.getState());
        try {
            String s = objectMapper.writeValueAsString(att);
            // 不能截断 JSON；截断后库中 attributes 看似有值，实际无法再解析。
            return s.length() > 1024 ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private void reject(IngestResult res, IngestMessage m, String reason, String quality) {
        res.setRejected(res.getRejected() + 1);
        res.getResults().add(item(m, "REJECTED", quality, reason));
    }

    private IngestResult.Item item(IngestMessage m, String status, String quality, String reason) {
        IngestResult.Item it = new IngestResult.Item();
        it.setPointCode(m.getPointCode());
        it.setCollectTime(m.getCollectTime());
        it.setStatus(status);
        it.setQuality(quality);
        it.setReason(reason);
        return it;
    }


    /**
     * 解析设备侧的 {@code collectTime}；**缺失或格式非法返回 {@code null}**，由调用方按整条
     * {@code REJECTED} 处理，不替设备编一个时间。
     *
     * <p>早期实现是「解析不了就取 {@code now()}」，那不只是数据不准，还会**掩盖设备离线**：
     * 设备发来坏时间戳，平台按「刚刚收到」盖章，而 {@link com.monitor.asset.DeviceStatusPolicy}
     * 只认 {@code last_report_time}——于是设备一直在报垃圾却永远显示在线，离线告警永远不会响。
     * 时间戳是设备的契约义务（message-contract §2），坏数据应当明确拒收、计入 {@code rejected}。</p>
     *
     * <p>带偏移的写法必须**换算**到平台时区（{@link Times#ZONE}），不能只取 {@code toLocalDateTime()}——
     * 那会丢掉偏移只留墙上时间：设备用 UTC 报 {@code 06:00Z} 会被存成 06:00 并当作 +08:00 回读，整错 8 小时。
     * 宽容度与查询侧一致，统一委托 {@link Times#parse}。</p>
     */
    private LocalDateTime parseCollectTime(String s) {
        try {
            return Times.parse(s, "collectTime");
        } catch (BizException e) {
            return null;
        }
    }

    /**
     * 解析平台侧的 {@code receiveTime}：没给就取当前时间——**这是对的**，
     * 它本就是「平台什么时候收到的」，兜底取 now() 不会凭空造出错误的时间语义。
     */
    private LocalDateTime parseReceiveTime(String s) {
        if (!notBlank(s)) return LocalDateTime.now();
        return parseCollectTime(s);
    }

    private static String messageKey(String deviceId, String messageId) {
        return deviceId + "\u0000" + messageId;
    }

    private boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
}
