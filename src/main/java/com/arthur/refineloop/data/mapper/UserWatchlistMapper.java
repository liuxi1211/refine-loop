package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.entity.UserWatchlistEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code user_watchlist} 数据访问。
 *
 * <p>唯一性由 {@code uk_user_watchlist(user_id, ts_code)} 兜底；服务层把唯一冲突翻译成 409。
 */
@Mapper
public interface UserWatchlistMapper extends BaseMapper<UserWatchlistEntity> {
}
