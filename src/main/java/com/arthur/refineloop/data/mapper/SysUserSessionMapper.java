package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.entity.SysUserSessionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * {@code sys_user_session} 数据访问：令牌查询与撤销。
 *
 * <p>撤销/续期都是「按 token_hash 定位一行」的固定更新，写成注解 SQL 避免在服务层拼条件；
 * 时间值由调用方注入 {@code Clock} 生成，SQL 不用数据库时间函数。
 */
@Mapper
public interface SysUserSessionMapper extends BaseMapper<SysUserSessionEntity> {

    /** 记录令牌最近使用时间（滑动续期不改变 expires_at，过期由签发时间决定）。 */
    @Update("""
            UPDATE sys_user_session
               SET last_used_at = #{now}, updated_at = #{now}
             WHERE token_hash = #{tokenHash} AND revoked = 0
            """)
    int touch(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    /** 单令牌撤销（登出）。 */
    @Update("""
            UPDATE sys_user_session
               SET revoked = 1, updated_at = #{now}
             WHERE token_hash = #{tokenHash} AND revoked = 0
            """)
    int revokeByTokenHash(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    /** 撤销某用户全部有效会话（改密/停用/删除账号）。 */
    @Update("""
            UPDATE sys_user_session
               SET revoked = 1, updated_at = #{now}
             WHERE user_id = #{userId} AND revoked = 0
            """)
    int revokeAllByUserId(@Param("userId") long userId, @Param("now") LocalDateTime now);
}
