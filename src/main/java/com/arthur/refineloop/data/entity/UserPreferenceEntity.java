package com.arthur.refineloop.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 账号域-用户交易偏好（表 {@code user_preference}，幂等键 {@code user_id + pref_key}）。
 *
 * <p>职责：按用户保存「交易习惯」类配置（周期偏好、风险偏好、仓位口径…）。
 * <p>不变量：{@code pref_key} 是稳定编码（发布后不可改），{@code pref_value} 一律字符串；
 * 复杂结构以 JSON 文本存储，由调用方约定 schema。
 */
@Getter
@Setter
@TableName("user_preference")
public class UserPreferenceEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String prefKey;
    private String prefValue;

    @Override
    protected Object idOf() {
        return id;
    }
}
