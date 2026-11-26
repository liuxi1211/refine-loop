package com.arthur.refineloop.judgment;

import com.arthur.refineloop.common.ApiException;

import java.util.regex.Pattern;

/**
 * 判断编号生成：{@code J-{date}-{pipeline}-{seq}}（能力详案 3.2 示例 {@code J-20261122-P1-0001}）。
 *
 * <p><b>为什么序号是「按 (trade_date, pipeline) 全局递增」而不是「按标的」</b>：编号是判断的全局标识，
 * 同一交易日的 P1 判断共享一个序列，任何人拿到编号就能看出「这是哪天、哪条流水线、第几份判断」；
 * 按标的编号会让同一天出现大量 {@code -0001}，失去可读性。
 *
 * <p><b>并发</b>：S4 是 per-stock 并行，多个标的会同时取序号。这里不做分布式锁（单机单库），
 * 而是「读最新 + 生成 + 由 {@code uk_judgment} 唯一索引兜底 + 冲突重试」——这是唯一不会在
 * JVM 重启/SQLite 单写者下失效的方案。
 *
 * <p><b>不变量</b>：编号 ≤ 40 字符（{@code judgment.judgment_id VARCHAR(40)}）；
 * 序号补零到 4 位，保证字典序 = 数值序（{@code selectLatestJudgmentId} 依赖这一点）。
 *
 * <p><b>传入的「最新编号」不属于本组时从 0001 重开</b>：编号本身已经编码了日期与流水线，
 * 拿别组的编号推序号只会产出一个错位的号；而「从 0001 重开」即使撞上已有行，
 * 也会被 {@code uk_judgment} 唯一索引拦住并触发重试，不会静默写坏数据。
 */
public class JudgmentIdGenerator {

    /** 编号前缀。 */
    public static final String PREFIX = "J";

    /** 序号上限：4 位定长，超出即数据异常（同日同流水线上万份判断不在真实业务范围内）。 */
    public static final int MAX_SEQUENCE = 9999;

    /** 编号长度上限（{@code judgment_id VARCHAR(40)}）。 */
    private static final int MAX_ID_LENGTH = 40;

    /**
     * 流水线 id：大写字母开头，允许大写/数字/连字符，总长 1~8（{@code judgment.pipeline_id VARCHAR(8)}）。
     *
     * <p>允许连字符是为了「同一流水线的手动/专用变体」（如 {@code P1-M}），但必须守住 8 位上限：
     * 编号里还要再拼日期与序号，超长会被 {@code MAX_ID_LENGTH} 拒绝。
     */
    private static final Pattern PIPELINE_ID = Pattern.compile("^[A-Z][A-Z0-9-]{0,7}$");
    private static final Pattern TRADE_DATE = Pattern.compile("^\\d{8}$");

    /**
     * 生成下一个编号。
     *
     * @param tradeDate        交易日 {@code yyyyMMdd}
     * @param pipelineId       流水线 id（大写字母开头，≤8 位）
     * @param latestJudgmentId 同 (tradeDate, pipelineId) 现有最新编号；首份为 null
     * @return 新编号
     * @throws ApiException 6001 入参非法/现有编号无法解析；6004 序号耗尽
     */
    public String next(String tradeDate, String pipelineId, String latestJudgmentId) {
        validateTradeDate(tradeDate);
        validatePipelineId(pipelineId);
        String prefix = PREFIX + "-" + tradeDate + "-" + pipelineId + "-";
        int sequence = latestJudgmentId == null || latestJudgmentId.isBlank()
                || !latestJudgmentId.startsWith(prefix)
                ? 1
                : parseSequence(latestJudgmentId) + 1;
        if (sequence > MAX_SEQUENCE) {
            throw ApiException.of(JudgmentErrorCode.SEQUENCE_EXHAUSTED,
                    "交易日 " + tradeDate + " 流水线 " + pipelineId + " 的判断序号已超过 " + MAX_SEQUENCE);
        }
        String judgmentId = prefix + String.format("%04d", sequence);
        if (judgmentId.length() > MAX_ID_LENGTH) {
            throw ApiException.of(JudgmentErrorCode.PAYLOAD_INVALID,
                    "判断编号超长（" + judgmentId.length() + " > " + MAX_ID_LENGTH + "）：" + judgmentId);
        }
        return judgmentId;
    }

    private void validateTradeDate(String tradeDate) {
        if (tradeDate == null || !TRADE_DATE.matcher(tradeDate).matches()) {
            throw ApiException.of(JudgmentErrorCode.PAYLOAD_INVALID,
                    "tradeDate 必须为 yyyyMMdd（当前 " + tradeDate + "）");
        }
    }

    private void validatePipelineId(String pipelineId) {
        if (pipelineId == null || !PIPELINE_ID.matcher(pipelineId).matches()) {
            throw ApiException.of(JudgmentErrorCode.PAYLOAD_INVALID,
                    "pipelineId 必须为大写字母开头、仅大写/数字/连字符、1~8 位（当前 " + pipelineId + "）");
        }
    }

    private int parseSequence(String latestJudgmentId) {
        int dash = latestJudgmentId.lastIndexOf('-');
        String tail = dash < 0 ? "" : latestJudgmentId.substring(dash + 1);
        if (tail.isEmpty() || !tail.chars().allMatch(Character::isDigit)) {
            throw ApiException.of(JudgmentErrorCode.PAYLOAD_INVALID,
                    "现有判断编号无法解析序号（期望 J-yyyyMMdd-pipeline-0001）：" + latestJudgmentId);
        }
        try {
            return Integer.parseInt(tail);
        } catch (NumberFormatException e) {
            throw ApiException.of(JudgmentErrorCode.PAYLOAD_INVALID,
                    "现有判断编号序号越界：" + latestJudgmentId);
        }
    }
}
