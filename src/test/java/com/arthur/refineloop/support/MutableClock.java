package com.arthur.refineloop.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** 可推进的假时钟（限流/接入单测用，避免真实等待）。 */
public final class MutableClock extends Clock {

    private long millis;

    public MutableClock(long millis) {
        this.millis = millis;
    }

    public static MutableClock at(String isoInstant) {
        return new MutableClock(Instant.parse(isoInstant).toEpochMilli());
    }

    public void advance(long deltaMillis) {
        millis += deltaMillis;
    }

    public void set(long millis) {
        this.millis = millis;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("Asia/Shanghai");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public long millis() {
        return millis;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis);
    }
}
