package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.provider.TushareSqlProvider;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

import java.util.List;
import java.util.Map;

/**
 * 41 张 Tushare 镜像表的统一 Mapper（MyBatis 动态 SQL Provider）。
 *
 * <p>这些表的列集合由 {@code TushareTableRegistry} 的 {@code fields} 串决定，因此不建 41 个类型化
 * Mapper（会与元数据漂移），而是通过 Provider 按「表名 + 白名单列 + 幂等键」生成 SQL。
 * 表名/列名的合法性由调用方（{@code IngestTableWriter} / 运维服务）依据注册表校验，
 * Provider 侧再做一次标识符正则兜底。
 *
 * <p>批量写入不在此接口循环：{@code IngestTableWriter} 用
 * {@code MyBatisBatchExecutor}（BATCH 执行器）复用同一条 MappedStatement。
 */
@Mapper
public interface TushareTableMapper {

    @InsertProvider(type = TushareSqlProvider.class, method = "insert")
    int insertRow(@Param("table") String table,
                  @Param("columns") List<String> columns,
                  @Param("row") Map<String, Object> row);

    @InsertProvider(type = TushareSqlProvider.class, method = "insertOrIgnore")
    int insertOrIgnore(@Param("table") String table,
                       @Param("columns") List<String> columns,
                       @Param("row") Map<String, Object> row);

    @InsertProvider(type = TushareSqlProvider.class, method = "upsert")
    int upsert(@Param("table") String table,
               @Param("columns") List<String> columns,
               @Param("keys") List<String> keys,
               @Param("row") Map<String, Object> row);

    @DeleteProvider(type = TushareSqlProvider.class, method = "deleteAll")
    int deleteAll(@Param("table") String table);

    @DeleteProvider(type = TushareSqlProvider.class, method = "deleteByKeys")
    int deleteByKeys(@Param("table") String table,
                     @Param("keys") List<String> keys,
                     @Param("keyValues") Map<String, Object> keyValues);

    @SelectProvider(type = TushareSqlProvider.class, method = "countAll")
    long countAll(@Param("table") String table);

    @SelectProvider(type = TushareSqlProvider.class, method = "maxValue")
    String maxValue(@Param("table") String table, @Param("column") String column);

    @SelectProvider(type = TushareSqlProvider.class, method = "selectColumn")
    List<String> selectColumn(@Param("table") String table,
                              @Param("column") String column,
                              @Param("whereSql") String whereSql,
                              @Param("orderBySql") String orderBySql,
                              @Param("limit") Integer limit);

    @SelectProvider(type = TushareSqlProvider.class, method = "groupCount")
    List<Map<String, Object>> groupCount(@Param("table") String table,
                                         @Param("column") String column,
                                         @Param("limit") int limit);

    @SelectProvider(type = TushareSqlProvider.class, method = "countFiltered")
    long countFiltered(@Param("table") String table,
                       @Param("codeColumn") String codeColumn,
                       @Param("dateColumn") String dateColumn,
                       @Param("code") String code,
                       @Param("start") String start,
                       @Param("end") String end);

    @SelectProvider(type = TushareSqlProvider.class, method = "preview")
    List<Map<String, Object>> preview(@Param("table") String table,
                                      @Param("columnsSql") String columnsSql,
                                      @Param("codeColumn") String codeColumn,
                                      @Param("dateColumn") String dateColumn,
                                      @Param("code") String code,
                                      @Param("start") String start,
                                      @Param("end") String end,
                                      @Param("orderBySql") String orderBySql,
                                      @Param("limit") int limit,
                                      @Param("offset") long offset);

    @SelectProvider(type = TushareSqlProvider.class, method = "tableInfo")
    List<Map<String, Object>> tableInfo(@Param("table") String table);
}
