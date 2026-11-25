package com.arthur.refineloop.common.util;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 标识生成器：runId、reportId、requestId、临时文件名统一走这里。
 *
 * <p>三种 ID 的适用场景：
 * <ul>
 *   <li>{@link #uuid32()} / {@link #uuid()}：纯随机，无排序语义，适合「只需要唯一」的场景；</li>
 *   <li>{@link #timestampedId(String)}：{@code R20261124153000-ab12} 形态，人能一眼看出时间，
 *       与本项目既有 runId/planId 格式一致（可读性优先）；</li>
 *   <li>{@link #ulid()}：26 位、按时间有序、字典序即时间序，适合做「需要按生成顺序排序」的主键。</li>
 * </ul>
 *
 * <p>单实例部署，不引入雪花算法（需要机器号且收益为零）。
 */
public final class Ids {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private Ids() {
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    /** 32 位无横线 UUID（日志/文件名里更省空间）。 */
    public static String uuid32() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 时间戳 ID：{@code prefix + yyyyMMddHHmmss + "-" + 4 位随机}，如 {@code R20261124153000-8k2p}。 */
    public static String timestampedId(String prefix) {
        return timestampedId(prefix, Clock.systemDefaultZone());
    }

    public static String timestampedId(String prefix, Clock clock) {
        String safePrefix = prefix == null ? "" : prefix;
        return safePrefix + LocalDateTime.now(clock).format(STAMP) + "-" + randomBase36(4);
    }

    /** ULID（Crockford Base32，26 位）：前 10 位时间、后 16 位随机，按生成时间有序。 */
    public static String ulid() {
        char[] out = new char[26];
        long timestamp = System.currentTimeMillis();
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (timestamp & 0x1F)];
            timestamp >>>= 5;
        }
        fillRandom(out, 10, 8, ThreadLocalRandom.current().nextLong());
        fillRandom(out, 18, 8, ThreadLocalRandom.current().nextLong());
        return new String(out);
    }

    /** 指定长度的 base36 随机串（小写字母+数字），用于短后缀。 */
    public static String randomBase36(int length) {
        if (length <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(Character.forDigit(ThreadLocalRandom.current().nextInt(36), 36));
        }
        return sb.toString();
    }

    private static void fillRandom(char[] out, int offset, int count, long bits) {
        for (int i = count - 1; i >= 0; i--) {
            out[offset + i] = CROCKFORD[(int) (bits & 0x1F)];
            bits >>>= 5;
        }
    }
}
