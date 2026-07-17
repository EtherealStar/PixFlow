package com.pixflow.harness.hooks;

import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.harness.hooks.config.HookProperties;
import com.pixflow.harness.hooks.error.HookError;
import com.pixflow.harness.hooks.internal.DispatchAccumulator;
import com.pixflow.harness.hooks.payload.HookPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DefaultHookRegistry implements HookRegistry {
    private final Map<HookEvent, List<HookCallback>> callbacksByEvent;

    private final HookProperties properties;

    private final ErrorNormalizer errorNormalizer;

    public DefaultHookRegistry(List<HookCallback> callbacks, HookProperties properties) {
        this(callbacks, properties, new ErrorNormalizer());
    }

    DefaultHookRegistry(List<HookCallback> callbacks, HookProperties properties, ErrorNormalizer errorNormalizer) {
        this.properties = properties == null ? new HookProperties() : properties;
        this.errorNormalizer = Objects.requireNonNull(errorNormalizer, "errorNormalizer");
        this.callbacksByEvent = indexCallbacks(callbacks == null ? List.of() : callbacks);
    }

    @Override
    public HookResult dispatch(HookEvent event, HookPayload payload) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(payload, "payload");
        DispatchAccumulator accumulator = new DispatchAccumulator(event, payload);
        for (HookCallback callback : callbacksByEvent.getOrDefault(event, List.of())) {
            try {
                HookResult result = callback.handle(event, accumulator.currentPayload());
                if (result == null) {
                    result = HookResult.noop();
                }
                accumulator.accept(result);
                if (result.blocked()) {
                    break;
                }
            } catch (Throwable throwable) {
                if (properties.isFailFastOnCallbackError()) {
                    throw throwable instanceof RuntimeException runtime
                            ? runtime
                            : new IllegalStateException(throwable);
                }
                // 回调异常只进入 metadata，不改变控制流，避免一个扩展点拖垮整条主链。
                try {
                    accumulator.appendHookError(toHookError(callback, throwable));
                } catch (Exception normalizationError) {
                    accumulator.appendHookError(
                            new HookError(
                                    callback.getClass().getName(),
                                    "INTERNAL",
                                    "Failed to normalize callback error: "
                                            + normalizationError.getClass().getSimpleName()));
                }
            }
        }
        return accumulator.toResult();
    }

    private HookError toHookError(HookCallback callback, Throwable throwable) {
        PixFlowException normalized = errorNormalizer.normalize(throwable);
        String safeMessage = Sanitizer.sanitizeMessage(normalized.getMessage());
        return new HookError(callback.getClass().getName(), normalized.category().name(), safeMessage);
    }

    private Map<HookEvent, List<HookCallback>> indexCallbacks(List<HookCallback> callbacks) {
        Map<HookEvent, List<HookCallback>> indexed = new EnumMap<>(HookEvent.class);
        for (HookCallback callback : callbacks) {
            if (callback == null || callback.supportedEvents() == null) {
                continue;
            }
            for (HookEvent event : callback.supportedEvents()) {
                indexed.computeIfAbsent(event, ignored -> new ArrayList<>()).add(callback);
            }
        }
        indexed.replaceAll((event, bucket) -> bucket.stream()
                .sorted(Comparator.comparingInt(HookCallback::order))
                .toList());
        return Map.copyOf(indexed);
    }
}
