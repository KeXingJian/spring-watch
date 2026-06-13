/*

import com.springwatch.SpringWatch;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Aspect
@Component
public class SpringWatchAspect {

    private static final Meter METER = GlobalOpenTelemetry.get().getMeter("spring-watch");
    private static final ConcurrentMap<String, DoubleHistogram> HISTS = new ConcurrentHashMap<>();
    private static final AttributeKey<String> METHOD_KEY = AttributeKey.stringKey("method");

    @Around("@annotation(annotation)")
    public Object around(ProceedingJoinPoint pjp, SpringWatch annotation) throws Throwable {
        String name = pjp.getSignature().getDeclaringTypeName() + "." + pjp.getSignature().getName();
        if (!annotation.value().isEmpty()) {
            name = annotation.value();
        }
        DoubleHistogram hist = HISTS.computeIfAbsent(name, n ->
                METER.histogramBuilder("method.duration")
                        .setDescription("方法耗时")
                        .setUnit("ms")
                        .build());
        long start = System.nanoTime();
        try {
            return pjp.proceed();
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            hist.record(costMs, Attributes.of(METHOD_KEY, name));
        }
    }
}
*/
