package com.arthur.refineloop.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 账号域-用户自选股（表 {@code user_watchlist}，幂等键 {@code user_id + ts_code}）。
 *
 * <p>职责：按用户隔离的股票选择；{@code stock_name} 是写入时的名称快照（改名不影响历史）。
 * <p>不变量：同一用户同一标的只有一行；{@code user_id} 必须来自当前登录态，禁止由请求体指定。
 */
@Getter
@Setter
@TableName("user_watchlist")
public class UserWatchlistEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String tsCode;
    private String stockName;
    private String groupName;
    private String note;
    private Integer sortNo;

    @Override
    protected Object idOf() {
        return id;
    }
}
