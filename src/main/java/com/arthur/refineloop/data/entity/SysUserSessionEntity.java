package com.arthur.refineloop.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 账号域-登录会话（表 {@code sys_user_session}，{@code token_hash} 唯一）。
 *
 * <p>职责：承载「已登录」的会话事实，支持按令牌撤销（登出/改密/停用账号）。
 * <p>不变量：只落 SHA-256 十六进制哈希，明文令牌只在登录响应里出现一次；过期与撤销都由
 * 服务层判定，不用数据库时间函数。
 */
@Getter
@Setter
@TableName("sys_user_session")
public class SysUserSessionEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String tokenHash;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private Boolean revoked;

    @Override
    protected Object idOf() {
        return id;
    }
}
