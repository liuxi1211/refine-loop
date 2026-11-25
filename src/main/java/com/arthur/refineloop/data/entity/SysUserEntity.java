package com.arthur.refineloop.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 账号域-用户账号（表 {@code sys_user}，{@code username} 唯一）。
 *
 * <p>职责：账号主体与登录凭据（{@code password_hash} 只存 PBKDF2 编码串，不可逆）。
 * <p>不变量：{@code username} 全库唯一；{@code role} 取 {@code UserRole} 的大写码；
 * {@code active=0} 的账号不能登录，但保留历史数据。
 */
@Getter
@Setter
@TableName("sys_user")
public class SysUserEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String passwordHash;
    private String displayName;
    private String role;
    private Boolean active;
    private LocalDateTime lastLoginAt;
    private String remark;

    @Override
    protected Object idOf() {
        return id;
    }
}
