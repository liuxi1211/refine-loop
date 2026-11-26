package com.arthur.refineloop.judgment;

import com.arthur.refineloop.config.JudgmentProperties;
import com.arthur.refineloop.data.mapper.SwIndustryMemberMapper;
import com.arthur.refineloop.judgment.model.Benchmark;

/**
 * 基准解析：基准指数取配置，行业取「目标交易日仍在成分内的申万一级」。
 *
 * <p><b>为什么行业必须按交易日算</b>：申万成分会调整、公司主营会变化；用「今天的行业」去标注半年前的
 * 判断，归因时相对行业基准就错了。因此查询口径是 {@code out_date} 为空或不早于 {@code trade_date}，
 * 取最新纳入的那一条（见 {@code SwIndustryMemberMapper.selectL1Code}）。
 *
 * <p><b>无归属不抛异常</b>：行业数据缺失是常态（新库未回填、次新股未纳入），此时
 * {@code industry_code} 留 null 并由调用方在 {@code data_gaps} 留痕——判断仍要存档（不存档等于丢失事实），
 * 只是归因时要人工补。
 */
public class BenchmarkResolver {

    private final SwIndustryMemberMapper industryMemberMapper;
    private final JudgmentProperties properties;

    public BenchmarkResolver(SwIndustryMemberMapper industryMemberMapper,
                             JudgmentProperties properties) {
        this.industryMemberMapper = industryMemberMapper;
        this.properties = properties;
    }

    /**
     * 解析基准。
     *
     * @param tsCode    标的代码
     * @param tradeDate 交易日 {@code yyyyMMdd}
     * @return 基准（行业可能为 null）
     */
    public Benchmark resolve(String tsCode, String tradeDate) {
        String industryCode = industryMemberMapper.selectL1Code(tsCode, tradeDate);
        return new Benchmark(properties.getBenchmarkIndexCode(), industryCode);
    }
}
