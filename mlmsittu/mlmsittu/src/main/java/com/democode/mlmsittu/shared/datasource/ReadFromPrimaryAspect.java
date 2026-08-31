package com.democode.mlmsittu.shared.datasource;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Applies {@link ReadFromPrimary}.
 *
 * <p>Ordered ahead of Spring's transaction advice so the pin is in place before a transaction —
 * and therefore before the routing decision — begins. After it, the annotation would be read
 * having already chosen a database.
 */
@Aspect
@Component
@Order(0)
public class ReadFromPrimaryAspect {

    @Around("@annotation(com.democode.mlmsittu.shared.datasource.ReadFromPrimary)"
            + " || @within(com.democode.mlmsittu.shared.datasource.ReadFromPrimary)")
    public Object pinToPrimary(ProceedingJoinPoint call) throws Throwable {
        RoutingDataSource.PrimaryOnly.pin();
        try {
            return call.proceed();
        } finally {
            RoutingDataSource.PrimaryOnly.unpin();
        }
    }
}
