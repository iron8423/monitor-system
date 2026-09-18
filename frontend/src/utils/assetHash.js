/**
 * 数字孪生资产哈希核对（复查清单 P0-5）。
 *
 * 背景：`digital_twin_scene.asset_sha256` 自 V11 起就有，但**从来没有人核对过**——
 * 它只是被写进去、被读出来。2026-09-18 那次事故（GLB 重建 → 哈希变了 → 有人直接改了
 * 已执行过的迁移文件 → Flyway 校验和不匹配、两个栈同时起不来）正是这条缺口的另一面：
 * 盘上的模型换了，系统里没有任何一处会说话。
 *
 * 这里补的就是那一句：把模型字节流算一遍 SHA-256，跟后端下发的那串比。
 * 比对在**浏览器**做，因为资产是从前端静态路径（`/models/...`）发的，
 * 后端进程根本读不到前端镜像里的文件。
 *
 * 三件事刻意不做：
 *   · 不 sha256 图片/预览图——只核对真正被加载进三维场景的那个资产；
 *   · 不在 3D_TILES 上硬算——那种资产由几百个分片组成，对 `tileset.json` 算哈希
 *     说明不了瓦片内容，算出来只会给人虚假的安心；
 *   · 不因为核对失败就拦住场景加载——模型坏了也要先把现场显示出来，
 *     核对结果是一条**告警**，不是一道闸门。
 */

/** 浏览器是否具备 WebCrypto（非安全上下文下 `crypto.subtle` 是 undefined）。 */
export function hashAvailable() {
  return typeof crypto !== 'undefined' && !!crypto.subtle && typeof crypto.subtle.digest === 'function'
}

/** ArrayBuffer → 64 位小写十六进制。 */
export async function sha256Hex(buffer) {
  const digest = await crypto.subtle.digest('SHA-256', buffer)
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

export const HASH_OK = 'ok'
export const HASH_MISMATCH = 'mismatch'
export const HASH_UNRECORDED = 'unrecorded'
export const HASH_UNSUPPORTED = 'unsupported'
export const HASH_SKIPPED = 'skipped'
export const HASH_ERROR = 'error'

/** 状态 → 人话。放到这里而不是各页面各写一份，免得同一件事两种说法。 */
export const HASH_TEXT = {
  [HASH_OK]: '资产与登记哈希一致',
  [HASH_MISMATCH]: '资产哈希与库中登记不一致',
  [HASH_UNRECORDED]: '库中未登记资产哈希',
  [HASH_UNSUPPORTED]: '当前浏览器无法计算哈希',
  [HASH_SKIPPED]: '该资产类型不做单文件核对',
  [HASH_ERROR]: '资产读取失败，无法核对',
}

/** 只有这两种状态该被当成「要人处理」。 */
export function isHashProblem(status) {
  return status === HASH_MISMATCH || status === HASH_ERROR
}

/**
 * 核对一个数字孪生场景配置。**任何分支都返回结果对象**（不抛异常）：
 * 调用点在大屏与运维页上，让"核对本身失败"去打断页面加载是最糟的取舍。
 *
 * @param {object} config `/v1/projects/{id}/digital-twin` 的响应
 * @returns {Promise<{status:string, projectId:number|null, assetUrl:string, expected:string,
 *                     actual:string|null, bytes:number|null, checkedAt:string, error?:string}>}
 */
export async function verifyAssetHash(config) {
  const base = {
    projectId: config?.projectId ?? null,
    assetUrl: config?.assetUrl || '',
    expected: String(config?.assetSha256 || '').trim().toLowerCase(),
    actual: null,
    bytes: null,
    checkedAt: new Date().toISOString(),
  }
  if (!config?.assetUrl || String(config.assetType || '').toUpperCase() !== 'GLB') {
    return { ...base, status: HASH_SKIPPED }
  }
  if (!hashAvailable()) {
    return { ...base, status: HASH_UNSUPPORTED }
  }
  try {
    // cache: 'no-store' 是刻意的：核对的意义是"现在盘上这一份是什么"，
    // 命中 HTTP 缓存就等于在核对一份可能已经被替换掉的旧副本。
    const res = await fetch(config.assetUrl, { cache: 'no-store' })
    if (!res.ok) {
      return { ...base, status: HASH_ERROR, error: `HTTP ${res.status}` }
    }
    const buf = await res.arrayBuffer()
    const actual = await sha256Hex(buf)
    return {
      ...base,
      bytes: buf.byteLength,
      actual,
      // 库里没登记哈希时不算"不一致"：那不是资产错了，而是这条记录缺一半。
      // 混为一谈会让第一次启用核对的部署满屏红字。
      status: !base.expected ? HASH_UNRECORDED : (actual === base.expected ? HASH_OK : HASH_MISMATCH),
    }
  } catch (e) {
    return { ...base, status: HASH_ERROR, error: e?.message || String(e) }
  }
}
