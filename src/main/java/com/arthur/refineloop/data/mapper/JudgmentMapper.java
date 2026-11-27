package com.arthur.refineloop.data.mapper;

import com.arthur.refineloop.data.entity.JudgmentEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * {@code judgment} 的数据访问：<b>写入只有一条路径，更新只允许两列</b>。
 *
 * <p><b>为什么不继承 {@code BaseMapper}</b>（与其它业务表不同）：{@code BaseMapper} 自带
 * {@code updateById / update(entity, wrapper) / deleteById} 等通用写方法，等于把「判断内容可改」
 * 变成调用方自觉。冻结语义（能力详案 3.3 契约 5、架构 §4.4）必须是<b>编译期</b>约束：
 * 本接口只暴露 {@link #insert} 与两个专用标记方法，{@code JudgmentMapperSurfaceTest} 用反射锁定这份白名单，
 * 任何人想加「改 payload」的方法都会让测试变红。
 *
 * <p>不变量：
 * <ul>
 *   <li>唯一可变异步只有 {@code superseded}（0→1）与 {@code invalidated_at}；</li>
 *   <li>不提供删除方法：判断记录不可删除，只可标注失效（总纲 5.1）；</li>
 *   <li>同组（ts_code+trade_date+pipeline_id）至多一行 {@code superseded=0}，由生成列 + 唯一索引兜底。</li>
 * </ul>
 */
@Mapper
public interface JudgmentMapper {

    /** 插入一条已冻结的判断（{@code frozen=1, superseded=0}）。 */
    @Insert("INSERT INTO judgment (judgment_id, trade_date, ts_code, pipeline_id, run_id, report_id, method_version, "
            + "direction, horizon_days, expire_at, confidence, payload, frozen, superseded, invalidated_at, created_at) "
            + "VALUES (#{judgmentId}, #{tradeDate}, #{tsCode}, #{pipelineId}, #{runId}, #{reportId}, #{methodVersion}, "
            + "#{direction}, #{horizonDays}, #{expireAt}, #{confidence}, #{payload}, #{frozen}, #{superseded}, "
            + "#{invalidatedAt}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(JudgmentEntity entity);

    @Select("SELECT * FROM judgment WHERE judgment_id = #{judgmentId}")
    JudgmentEntity selectByJudgmentId(@Param("judgmentId") String judgmentId);

    /** 同组当前有效判断（superseded=0）；正常应至多一行。 */
    @Select("SELECT * FROM judgment WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} "
            + "AND pipeline_id = #{pipelineId} AND superseded = 0 ORDER BY id DESC LIMIT 1")
    JudgmentEntity selectActive(@Param("tsCode") String tsCode,
                                @Param("tradeDate") String tradeDate,
                                @Param("pipelineId") String pipelineId);

    /**
     * 同组最新 judgment_id（按字符串倒序）：编号形如 {@code J-20261122-P1-0001}，
     * 前缀固定、序号定长补零，因此字典序即数值序。
     */
    @Select("SELECT judgment_id FROM judgment WHERE trade_date = #{tradeDate} "
            + "AND pipeline_id = #{pipelineId} ORDER BY judgment_id DESC LIMIT 1")
    String selectLatestJudgmentId(@Param("tradeDate") String tradeDate,
                                  @Param("pipelineId") String pipelineId);

    /**
     * 统计「更早交易日、仍存续、同方向」的有效判断条数——用于判定 {@code direction.is_reaffirm}。
     *
     * <p>存续口径是「{@code expire_at} 不早于<b>当前交易日</b>」（不是不早于新判断的到期日）：
     * 重申的语义是「上一份判断此刻还没到期，本次只是同一逻辑的延续」（能力详案 9.2）。
     *
     * <p>排除同交易日：同日重跑是「修正同一个判断」，不是「重申一个已存续的判断」
     * （同一判断实例只应产生一份首判断，否则 M3 胜率分母会被同日重跑污染）。
     *
     * @param asOfDate 当前交易日 ISO 形态 {@code yyyy-MM-dd}（与 {@code expire_at} 文本同格式可比）
     */
    @Select("SELECT COUNT(*) FROM judgment WHERE ts_code = #{tsCode} AND direction = #{direction} "
            + "AND superseded = 0 AND trade_date < #{tradeDate} AND expire_at >= #{asOfDate}")
    int countPriorActiveSameDirection(@Param("tsCode") String tsCode,
                                      @Param("direction") String direction,
                                      @Param("tradeDate") String tradeDate,
                                      @Param("asOfDate") String asOfDate);

    /** 同组旧判断置为被取代（唯一允许的「加上 superseded」路径）。 */
    @Update("UPDATE judgment SET superseded = 1, invalidated_at = #{invalidatedAt} "
            + "WHERE judgment_id = #{judgmentId} AND superseded = 0")
    int markSuperseded(@Param("judgmentId") String judgmentId,
                       @Param("invalidatedAt") LocalDateTime invalidatedAt);

    /** 人工标注失效（不改 superseded：该判断仍是其时间点上的唯一有效判断）。 */
    @Update("UPDATE judgment SET invalidated_at = #{invalidatedAt} "
            + "WHERE judgment_id = #{judgmentId} AND invalidated_at IS NULL")
    int markInvalidated(@Param("judgmentId") String judgmentId,
                        @Param("invalidatedAt") LocalDateTime invalidatedAt);
}
