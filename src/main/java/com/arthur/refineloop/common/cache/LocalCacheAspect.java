package com.arthur.refineloop.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link LocalCache} / {@link LocalCacheEvict} 的切面实现。
 *
 * <p>关键实现点（都是踩过的坑）：
 * <ul>
 *   <li>Caffeine 不接受 null 值，这里用 {@link #NULL} 哨兵包装，保证「返回 null」也能被缓存；</li>
 *   <li>{@code ProceedingJoinPoint#proceed()} 抛的是 Throwable，而 Caffeine 的 loader 只允许
 *       RuntimeException：用 {@link CacheInvocationException} 原样穿透，再在切面外层还原，
 *       不吞异常也不改异常类型；</li>
 *   <li>SpEL 表达式解析结果按表达式字符串缓存，避免每次调用都重新解析（反射解析是热点开销）；</li>
 *   <li>{@code sync=true} 依赖 Caffeine 的原子 {@code get(key, mappingFunction)}：
 *       同一 key 并发只有一个线程执行方法体，天然防击穿。</li>
 * </ul>
 */
@Aspect
@Component
public class LocalCacheAspect {

    /** null 值哨兵（Caffeine 不允许 null value）。 */
    private static final Object NULL = new Object();

    private final LocalCacheRegistry registry;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> parsedExpressions = new ConcurrentHashMap<>();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public LocalCacheAspect(LocalCacheRegistry registry) {
        this.registry = registry;
    }

    @Around("@annotation(localCache)")
    public Object cacheAround(ProceedingJoinPoint joinPoint, LocalCache localCache) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        String name = resolveName(localCache.name(), method);
        String key = resolveKey(localCache.key(), method, joinPoint.getArgs());
        Cache<String, Object> cache = registry.cache(name, localCache.ttlSeconds(), localCache.maxSize());

        if (!localCache.sync()) {
            Object present = cache.getIfPresent(key);
            if (present != null) {
                return unwrap(present);
            }
            Object value = wrap(proceed(joinPoint));
            cache.put(key, value);
            return unwrap(value);
        }

        try {
            return unwrap(cache.get(key, ignored -> wrap(proceed(joinPoint))));
        } catch (CacheInvocationException e) {
            throw e.getCause();
        }
    }

    @AfterReturning("@annotation(evict)")
    public void evictAfterReturning(JoinPoint joinPoint, LocalCacheEvict evict) {
        if (evict.key() == null || evict.key().isBlank()) {
            registry.invalidateAll(evict.name());
            return;
        }
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        String key = resolveKey(evict.key(), method, joinPoint.getArgs());
        registry.invalidate(evict.name(), key);
    }

    /* ---------------- 内部实现 ---------------- */

    private Object proceed(ProceedingJoinPoint joinPoint) {
        try {
            return joinPoint.proceed();
        } catch (Throwable throwable) {
            throw new CacheInvocationException(throwable);
        }
    }

    private static Object wrap(Object value) {
        return value == null ? NULL : value;
    }

    private static Object unwrap(Object value) {
        return value == NULL ? null : value;
    }

    private static String resolveName(String configured, Method method) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }

    private String resolveKey(String expression, Method method, Object[] args) {
        if (expression == null || expression.isBlank()) {
            return defaultKey(args);
        }
        Expression parsed = parsedExpressions.computeIfAbsent(expression, parser::parseExpression);
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("method", method.getName());
        Object[] safeArgs = args == null ? new Object[0] : args;
        String[] names = parameterNameDiscoverer.getParameterNames(method);
        for (int i = 0; i < safeArgs.length; i++) {
            // 同时提供 #p0/#a0（位置）与 #参数名（可读）两种写法
            context.setVariable("p" + i, safeArgs[i]);
            context.setVariable("a" + i, safeArgs[i]);
            if (names != null && i < names.length) {
                context.setVariable(names[i], safeArgs[i]);
            }
        }
        Object value = parsed.getValue(context);
        return String.valueOf(value);
    }

    private static String defaultKey(Object[] args) {
        if (args == null || args.length == 0) {
            return "noargs";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }

    /** 把方法体的 Throwable 穿透 Caffeine loader 的载体异常（不该被业务看见）。 */
    private static final class CacheInvocationException extends RuntimeException {

        private CacheInvocationException(Throwable cause) {
            super(cause);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
