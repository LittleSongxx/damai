package org.javaup.ai.infra.repeat.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.javaup.ai.infra.repeat.RepeatExecuteLimit;
import org.redisson.api.RedissonClient;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;

@Slf4j
@Aspect
public class RepeatExecuteLimitAspect {

    private static final ExpressionParser SPEL_PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    private final RedissonClient redissonClient;

    public RepeatExecuteLimitAspect(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Around("@annotation(repeatExecuteLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RepeatExecuteLimit repeatExecuteLimit) throws Throwable {
        String limitKey = resolveLimitKey(joinPoint, repeatExecuteLimit);
        Duration ttl = Duration.ofMillis(repeatExecuteLimit.timeUnit().toMillis(repeatExecuteLimit.duration()));

        var bucket = redissonClient.getBucket(limitKey);
        boolean acquired = bucket.setIfAbsent("1", ttl);
        if (!acquired) {
            throw new IllegalStateException(repeatExecuteLimit.message());
        }
        try {
            return joinPoint.proceed();
        } finally {
            try {
                bucket.delete();
            } catch (Exception ex) {
                log.warn("failed to release repeat execute limit key: {}", limitKey, ex);
            }
        }
    }

    private String resolveLimitKey(ProceedingJoinPoint joinPoint, RepeatExecuteLimit annotation) {
        StringBuilder builder = new StringBuilder("repeat:");
        String name = StringUtils.hasText(annotation.name())
                ? annotation.name()
                : ((MethodSignature) joinPoint.getSignature()).getDeclaringType().getSimpleName()
                + "." + ((MethodSignature) joinPoint.getSignature()).getName();
        builder.append(name);

        String[] keyExpressions = annotation.keys();
        if (keyExpressions.length > 0) {
            Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
            String[] paramNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
            Object[] args = joinPoint.getArgs();

            EvaluationContext context = new StandardEvaluationContext();
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length && i < args.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }

            for (String keyExpression : keyExpressions) {
                builder.append(':').append(SPEL_PARSER.parseExpression(keyExpression).getValue(context, String.class));
            }
        }
        return builder.toString();
    }
}
