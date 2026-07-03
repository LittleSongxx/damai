package org.javaup.ai.infra.lock.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.javaup.ai.infra.lock.LockTimeOutStrategy;
import org.javaup.ai.infra.lock.ServiceLock;
import org.javaup.ai.infra.lock.ServiceLocker;
import org.javaup.ai.infra.lock.factory.ServiceLockFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

@Slf4j
@Aspect
public class ServiceLockAspect {

    private static final ExpressionParser SPEL_PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    private final ServiceLockFactory lockFactory;

    public ServiceLockAspect(ServiceLockFactory lockFactory) {
        this.lockFactory = lockFactory;
    }

    @Around("@annotation(serviceLock)")
    public Object around(ProceedingJoinPoint joinPoint, ServiceLock serviceLock) throws Throwable {
        String lockName = resolveLockName(joinPoint, serviceLock);
        String[] resolvedKeys = resolveKeys(joinPoint, serviceLock);
        String fullLockKey = buildFullKey(lockName, resolvedKeys);

        ServiceLocker locker = lockFactory.get(serviceLock.lockType());
        boolean acquired = locker.tryLock(fullLockKey, serviceLock.waitTime(), serviceLock.timeUnit());
        if (!acquired) {
            return handleTimeout(serviceLock, lockName, resolvedKeys);
        }
        try {
            return joinPoint.proceed();
        } finally {
            locker.unlock(fullLockKey);
        }
    }

    private String resolveLockName(ProceedingJoinPoint joinPoint, ServiceLock serviceLock) {
        if (StringUtils.hasText(serviceLock.name())) {
            return serviceLock.name();
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return signature.getDeclaringType().getSimpleName() + "." + signature.getName();
    }

    private String[] resolveKeys(ProceedingJoinPoint joinPoint, ServiceLock serviceLock) {
        String[] keyExpressions = serviceLock.keys();
        if (keyExpressions.length == 0) {
            return new String[0];
        }
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        String[] paramNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        Object[] args = joinPoint.getArgs();

        EvaluationContext context = new StandardEvaluationContext();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length && i < args.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }

        String[] resolved = new String[keyExpressions.length];
        for (int i = 0; i < keyExpressions.length; i++) {
            resolved[i] = String.valueOf(SPEL_PARSER.parseExpression(keyExpressions[i]).getValue(context, String.class));
        }
        return resolved;
    }

    private String buildFullKey(String lockName, String[] keys) {
        StringBuilder builder = new StringBuilder("lock:");
        builder.append(lockName);
        for (String key : keys) {
            builder.append(':').append(key);
        }
        return builder.toString();
    }

    private Object handleTimeout(ServiceLock serviceLock, String lockName, String[] keys) {
        return switch (serviceLock.lockTimeoutStrategy()) {
            case FAIL -> throw new IllegalStateException(
                    "failed to acquire distributed lock: " + buildFullKey(lockName, keys));
            case SKIP -> {
                log.warn("skipping due to lock timeout: {}", buildFullKey(lockName, keys));
                yield null;
            }
            case RETRY -> throw new IllegalStateException(
                    "lock retry not supported yet: " + buildFullKey(lockName, keys));
        };
    }
}
