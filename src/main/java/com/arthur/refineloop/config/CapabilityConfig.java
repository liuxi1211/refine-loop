package com.arthur.refineloop.config;

import com.arthur.refineloop.capability.runtime.FactStore;
import com.arthur.refineloop.capability.runtime.TradeCalTradingDayCalendar;
import com.arthur.refineloop.capability.runtime.TradingDayCalendar;
import com.arthur.refineloop.capability.runtime.UnitRegistry;
import com.arthur.refineloop.capability.runtime.UnitVersionStore;
import com.arthur.refineloop.capability.runtime.validation.ClasspathTagVocabulary;
import com.arthur.refineloop.capability.runtime.validation.TagVocabulary;
import com.arthur.refineloop.capability.runtime.validation.ValidationChain;
import com.arthur.refineloop.capability.runtime.validation.ValidationValidators;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRule;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRuleRegistry;
import com.arthur.refineloop.capability.runtime.validation.rule.JudgmentRuleSetLoader;
import com.arthur.refineloop.capability.runtime.validation.validators.FactsUsageValidator;
import com.arthur.refineloop.capability.runtime.validation.validators.JudgmentRuleValidator;
import com.arthur.refineloop.capability.runtime.validation.validators.ReportNumberScanner;
import com.arthur.refineloop.capability.runtime.validation.validators.SchemaValidator;
import com.arthur.refineloop.capability.runtime.validation.validators.TagVocabularyValidator;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;
import com.arthur.refineloop.data.mapper.UnitFactMapper;
import com.arthur.refineloop.data.mapper.UnitVersionMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * 能力单元框架装配：注册表、事实存取、版本登记、交易日历、L3 校验链。
 *
 * <p>运行时类保持为纯类（便于单测直接 new），由本配置显式装配；这里是唯一能看到
 * 「校验链由哪五个校验器、以什么顺序组装」的地方，与架构 §4.2.5 一一对应。
 */
@Configuration
public class CapabilityConfig {

    @Bean
    public UnitRegistry unitRegistry() {
        return new UnitRegistry();
    }

    @Bean
    public FactStore factStore(UnitFactMapper unitFactMapper, TransactionTemplate transactionTemplate, Clock clock) {
        return new FactStore(unitFactMapper, transactionTemplate, clock);
    }

    @Bean
    public UnitVersionStore unitVersionStore(UnitVersionMapper unitVersionMapper, Clock clock) {
        return new UnitVersionStore(unitVersionMapper, clock);
    }

    /** 交易日历：降级判断的「5 个交易日」有效期依赖它。 */
    @Bean
    public TradingDayCalendar tradingDayCalendar(TushareLookupMapper lookupMapper) {
        return new TradeCalTradingDayCalendar(lookupMapper);
    }

    /** 受控词表：M1 走 classpath 资源，T15 换 logic_tag_dict 实现时只替换本 Bean。 */
    @Bean
    public TagVocabulary tagVocabulary() {
        return new ClasspathTagVocabulary();
    }

    @Bean
    public JudgmentRuleSetLoader judgmentRuleSetLoader() {
        return new JudgmentRuleSetLoader();
    }

    /**
     * 判断规则注册表：规则实现由各单元以 Bean 形式提供，当前可能为空（M1 仅主判断单元有规则）。
     *
     * <p>用 {@link ObjectProvider} 而不是 {@code List<JudgmentRule>} 参数：
     * 零实现时后者会让启动失败，而「还没有单元特有规则」是合法状态。
     */
    @Bean
    public JudgmentRuleRegistry judgmentRuleRegistry(ObjectProvider<JudgmentRule> rules) {
        List<JudgmentRule> implementations = rules.orderedStream().toList();
        return new JudgmentRuleRegistry(implementations);
    }

    /** L3 五重校验链：顺序即架构文档 §4.2.5 的顺序，改动需同步文档与黄金样本基线。 */
    @Bean
    public ValidationChain validationChain(TagVocabulary tagVocabulary,
                                           JudgmentRuleSetLoader ruleSetLoader,
                                           JudgmentRuleRegistry ruleRegistry) {
        ValidationValidators validators = new ValidationValidators(
                new SchemaValidator(),
                new FactsUsageValidator(),
                new TagVocabularyValidator(tagVocabulary),
                new JudgmentRuleValidator(ruleSetLoader, ruleRegistry),
                new ReportNumberScanner());
        return new ValidationChain(validators);
    }
}
