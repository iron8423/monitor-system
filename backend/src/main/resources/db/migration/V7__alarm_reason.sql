-- 设备告警的「成因」（验收第 5 条扩展：数据质量异常 / 数据延迟上报）。
--
-- 为什么需要一列，而不是把成因塞进现有的 snapshot：
--   `snapshot` 是 AlarmService 在**读取时**按当前设备状态现算的 VO 字段，库里根本没有这一列。
--   而「成因」讲的是**当时为什么开这条警情**，是历史事实，不能从当前状态反推——
--   设备掉线之后，一条早先因数据质量开的警情不该被重新解说成「离线」。
--   所以它必须落库，且落库之后 snapshot 读它即可，不必再猜。
--
-- 为什么非区分不可（V7 之前不区分是对的，现在不对了）：
--   在此之前 alarm_type = DEVICE 只有一种成因（离线），于是 DeviceAlarmMonitor 用
--   「该设备有没有未解除的 DEVICE 告警」当作「已经告过警了」的判据，等价且够用。
--   加上数据质量/延迟两类之后这个等价关系断了：一台设备可以同时在「数据不可信」
--   与「已经掉线」两件事上各欠一条警情，而两条必须各管各的——否则后到的成因会被
--   先到的掩盖住，谁也开不出来，与 B-14（设备告警整类漏算）是同一类错误。
--   因此两个监视器都改为按 (device_id, alarm_reason) 找未解除警情。
--
-- 存量数据回填 OFFLINE：V7 之前产生的 DEVICE 告警只有离线一种来源（07 套件与演示库
-- 里的都是），标成 OFFLINE 是**事实**而不是猜测。POINT 类型警情不适用本列，保持 NULL。
ALTER TABLE alarm ADD COLUMN alarm_reason VARCHAR(32);

UPDATE alarm SET alarm_reason = 'OFFLINE' WHERE alarm_type = 'DEVICE';
