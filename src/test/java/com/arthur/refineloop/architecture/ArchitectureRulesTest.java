package com.arthur.refineloop.architecture;

import com.arthur.refineloop.capability.spi.annotation.CapabilityUnit;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * 《开发规范 01-工程结构与包结构》的机器校验：依赖矩阵、分层红线、命名。
 *
 * <p>为什么要有这层：包边界靠人记一定会腐化（历史上 `web` 直连 `data.mapper`、`common` 里放
 * `@RestController` 都是这样发生的）。规则写成测试后，违反约定在 `mvn test` 阶段就变红。
 *
 * <p><b>唯一权威矩阵</b>见规范 01 §4；本类逐条实现。`config` 是 Spring 装配根（唯一允许"知道所有层"
 * 的包），因此不参与"禁止反向依赖"的断言，但被依赖方向仍受限（只允许被取用配置属性）。
 *
 * <p>新增顶层包时必须同步：<ol>
 *   <li>在规范 01 §3/§4 的矩阵里加一行；</li>
 *   <li>在本类里为它补一条禁止反向依赖的规则，否则规则不生效。</li>
 * </ol>
 */
@AnalyzeClasses(packages = "com.arthur.refineloop", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    private static final String RESULT = "com.arthur.refineloop.common.Result";
    private static final String JDBC_TEMPLATE = "org.springframework.jdbc.core.JdbcTemplate";
    private static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";

    // ================================================================ 依赖方向

    @ArchTest
    static final ArchRule common_只依赖基础设施自身 = noClasses()
            .that().resideInAPackage("com.arthur.refineloop.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.arthur.refineloop.ops..", "com.arthur.refineloop.tushare..", "com.arthur.refineloop.user..",
                    "com.arthur.refineloop.data..", "com.arthur.refineloop.web..", "com.arthur.refineloop.config..",
                    "com.arthur.refineloop.capability..")
            .because("common 是地基：不得依赖任何业务域或装配层（规范 01 §4）");

    @ArchTest
    static final ArchRule data_不得反向依赖领域与web = noClasses()
            .that().resideInAPackage("com.arthur.refineloop.data..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.arthur.refineloop.ops..", "com.arthur.refineloop.tushare..",
                    "com.arthur.refineloop.user..", "com.arthur.refineloop.web..",
                    "com.arthur.refineloop.capability..")
            .because("data 是最底层数据访问，不得反向依赖领域与 web（规范 01 §4）");

    @ArchTest
    static final ArchRule capability不得依赖上层领域 = noClasses()
            .that().resideInAPackage("com.arthur.refineloop.capability..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.arthur.refineloop.ops..", "com.arthur.refineloop.tushare..",
                    "com.arthur.refineloop.user..", "com.arthur.refineloop.web..",
                    "com.arthur.refineloop.pipeline..", "com.arthur.refineloop.judgment..",
                    "com.arthur.refineloop.report..", "com.arthur.refineloop.version..")
            .because("capability 是核心域，只允许依赖 common/config/data/spi（规范 01 §4）");

    @ArchTest
    static final ArchRule capability_spi不得依赖运行时与数据层 = noClasses()
            .that().resideInAPackage("com.arthur.refineloop.capability.spi..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.arthur.refineloop.capability.runtime..",
                    "com.arthur.refineloop.capability.units..",
                    "com.arthur.refineloop.data..")
            .because("SPI 是契约层，依赖方向只能是 units → runtime → spi（规范 01 §4）");

    @ArchTest
    static final ArchRule capability_runtime不得依赖units = noClasses()
            .that().resideInAPackage("com.arthur.refineloop.capability.runtime..")
            .should().dependOnClassesThat().resideInAPackage("com.arthur.refineloop.capability.units..")
            .because("运行时不得反向依赖具体单元；单元由注册器按注解装配");

    @ArchTest
    static final ArchRule capability_units之间禁止互调 = classes()
            .that().resideInAPackage("com.arthur.refineloop.capability.units..")
            .should(new ArchCondition<>("单元不得依赖另一个带 @CapabilityUnit 的单元") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    item.getDirectDependenciesFromSelf().forEach(dependency -> {
                        JavaClass target = dependency.getTargetClass();
                        if (!target.equals(item)
                                && target.getPackageName().startsWith("com.arthur.refineloop.capability.units.")
                                && target.isAnnotatedWith(CapabilityUnit.class)) {
                            events.add(SimpleConditionEvent.violated(dependency,
                                    item.getFullName() + " 依赖了另一个能力单元 "
                                            + target.getFullName() + "（单元之间禁止互调）"));
                        }
                    });
                }
            })
            .allowEmptyShould(true)
            .because("单元协作只通过 FactBundle；互调会造成隐式编排与版本耦合");

    @ArchTest
    static final ArchRule 只有llm包可直接使用ChatClient = noClasses()
            .that().resideOutsideOfPackage("com.arthur.refineloop.capability.runtime.llm..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.ai.chat.client.ChatClient")
            .because("ChatClient 直连点必须收敛在 LlmInvoker/Provider 所在包（T7）");

    @ArchTest
    static final ArchRule 校验链必须是纯函数 = noClasses()
            .that().resideInAPackage("com.arthur.refineloop.capability.runtime.validation..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.arthur.refineloop.data..", "com.arthur.refineloop.judgment..",
                    "com.arthur.refineloop.pipeline..", "com.arthur.refineloop.report..")
            .because("L3 校验必须可单测、可黄金样本回放：不得读库、不得依赖流水线/存档层（架构 §4.2.5）");

    @ArchTest
    static final ArchRule 解读单元实现必须位于units包 = classes()
            .that().areAssignableTo("com.arthur.refineloop.capability.runtime.llm.AbstractInterpretUnit")
            .and().haveSimpleNameNotEndingWith("AbstractInterpretUnit")
            .should().resideInAPackage("com.arthur.refineloop.capability.units..")
            .allowEmptyShould(true)
            .because("完整模板服务具体单元：实现类归 capability.units（runtime 不得内含业务单元，规范 01 §4）");

    @ArchTest
    static final ArchRule 领域包不得依赖web = noClasses()
            .that().resideInAnyPackage("com.arthur.refineloop.ops..", "com.arthur.refineloop.tushare..",
                    "com.arthur.refineloop.user..")
            .should().dependOnClassesThat().resideInAPackage("com.arthur.refineloop.web..")
            .because("web 是适配层，依赖方向只能是 web → 领域（规范 01 §4）");

    @ArchTest
    static final ArchRule 账号域不得依赖其它业务域 = noClasses()
            .that().resideInAPackage("com.arthur.refineloop.user..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.arthur.refineloop.ops..", "com.arthur.refineloop.tushare..")
            .because("账号域只允许依赖 common/config/data；与运维、数据接入域无耦合（规范 01 §4）");

    @ArchTest
    static final ArchRule tushare不得依赖ops = noClasses()
            .that().resideInAPackage("com.arthur.refineloop.tushare..")
            .should().dependOnClassesThat().resideInAPackage("com.arthur.refineloop.ops..")
            .because("ops 是读模型域，依赖 tushare；反向会造成域间环（规范 01 §4）");

    @ArchTest
    static final ArchRule web不得依赖数据访问细节 = noClasses()
            .that().resideInAPackage("com.arthur.refineloop.web..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.arthur.refineloop.data.entity..", "com.arthur.refineloop.data.mapper..", "com.arthur.refineloop.data.provider..", "com.arthur.refineloop.data.batch..", "com.arthur.refineloop.data.handler..")
            .because("web 只能依赖领域服务；直连 mapper/entity 会把持久化细节泄进接口层（红线 2）");

    @ArchTest
    static final ArchRule 生产代码禁止JdbcTemplate = noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName(JDBC_TEMPLATE)
            .because("SQL 统一经 MyBatis Mapper 层；JdbcTemplate 只允许出现在测试夹具（红线 3）");

    // ================================================================ 分层红线

    @ArchTest
    static final ArchRule Controller必须位于web包 = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().resideInAPackage("com.arthur.refineloop.web..")
            .because("common 只放基础设施，@RestController 属 web 适配层（红线 2）");

    @ArchTest
    static final ArchRule Controller必须返回Result = methods()
            .that().arePublic()
            .and().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .should(new ArchCondition<>("返回 Result<T> 统一信封") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    String returnType = method.getReturnType().toErasure().getName();
                    if (!RESULT.equals(returnType)) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " 返回 " + returnType + "，应返回 " + RESULT + "（红线 8）"));
                    }
                }
            })
            .because("所有业务接口统一 Result<T>，前端只以 code 判定成功（规范 04 §3.1）");

    @ArchTest
    static final ArchRule Controller禁止事务 = noMethods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .should().beAnnotatedWith(TRANSACTIONAL)
            .because("事务边界在 service 写方法，Controller 开事务会把 HTTP 生命周期带进事务（规范 03 §8）");

    @ArchTest
    static final ArchRule 业务服务不得放在web包 = noClasses()
            .that().areAnnotatedWith(Service.class)
            .should().resideInAPackage("com.arthur.refineloop.web..")
            .because("web 只做适配；服务放领域包（规范 01 §3）");

    @ArchTest
    static final ArchRule 禁止SystemOut与printStackTrace = classes()
            .should(new ArchCondition<>("不使用 System.out/err 与 printStackTrace") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    item.getMethodCallsFromSelf().stream()
                            .filter(call -> call.getTarget().getName().equals("printStackTrace"))
                            .forEach(call -> events.add(SimpleConditionEvent.violated(call,
                                    call.getOrigin().getFullName() + " 调用了 printStackTrace（用 log.error）")));
                    item.getFieldAccessesFromSelf().stream()
                            .filter(access -> access.getTargetOwner().getName().equals("java.lang.System")
                                    && List.of("out", "err").contains(access.getTarget().getName()))
                            .forEach(access -> events.add(SimpleConditionEvent.violated(access,
                                    access.getOrigin().getFullName() + " 访问了 System."
                                            + access.getTarget().getName() + "（用 slf4j）")));
                }
            })
            .because("日志统一 slf4j + traceId（红线 9）");

    @ArchTest
    static final ArchRule 禁止遗留时间类型 = classes()
            .should(new ArchCondition<>("不使用 java.util.Date / java.sql.Timestamp / Instant 字段") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    if (item.getPackageName().equals("com.arthur.refineloop.data.handler")) {
                        return;   // JDBC 类型处理器需要与驱动交互（唯一例外）
                    }
                    for (JavaField field : item.getFields()) {
                        String type = field.getRawType().getName();
                        if (Set.of("java.util.Date", "java.sql.Timestamp", "java.time.Instant").contains(type)) {
                            events.add(SimpleConditionEvent.violated(field,
                                    field.getFullName() + " 使用了 " + type + "（统一 LocalDateTime，红线 7）"));
                        }
                    }
                }
            })
            .because("时间只有两种口径：LocalDateTime 与 yyyyMMdd 字符串（规范 02 §6）");

    // ================================================================ 命名

    @ArchTest
    static final ArchRule Mapper命名 = classes()
            .that().resideInAPackage("com.arthur.refineloop.data.mapper..")
            .should().haveSimpleNameEndingWith("Mapper")
            .because("后缀即契约：data.mapper 只放 *Mapper（规范 03 §1）");

    @ArchTest
    static final ArchRule Entity命名 = classes()
            .that().resideInAPackage("com.arthur.refineloop.data.entity..")
            .should().haveSimpleNameEndingWith("Entity")
            .because("后缀即契约：data.entity 只放 *Entity（规范 03 §1）");

    @ArchTest
    static final ArchRule Controller命名 = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().haveSimpleNameEndingWith("Controller")
            .because("后缀即契约：web 只放 *Controller（规范 03 §1）");

    @ArchTest
    static final ArchRule 工具类必须final且有私有构造 = classes()
            .that().resideInAPackage("com.arthur.refineloop.common.util..")
            .and().areTopLevelClasses()
            .should().haveModifier(JavaModifier.FINAL)
            .andShould().haveOnlyPrivateConstructors()
            .because("*s / *Utils 是无状态工具：final + 私有构造（规范 03 §2.1）");

    // 说明：不在本类做"包级循环依赖"断言——`config` 是装配根，天然形成 config ↔ 领域 的双向引用；
    // 领域之间的环由上面每对包的单向禁令覆盖（common ← data ← tushare ← ops ← web 的 DAG）。
}
