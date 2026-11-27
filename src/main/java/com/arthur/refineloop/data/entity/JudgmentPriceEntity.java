package com.arthur.refineloop.data.entity;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 判断基准快照使用的价格投影（非独立表）：个股日线+复权因子，或指数日线。
 *
 * <p>close 为元/点，adjFactor 为复权因子（指数投影恒为 1，由查询显式返回）。
 */
@Getter
@Setter
public class JudgmentPriceEntity {

    /** 收盘价：个股为元，指数为点位。 */
    private BigDecimal close;

    /** 复权因子；指数无除权概念，固定为 1。 */
    private BigDecimal adjFactor;
}
