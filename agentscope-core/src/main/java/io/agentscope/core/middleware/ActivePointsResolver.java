/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.middleware;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Resolves a middleware's per-extension-point participation set: an explicit
 * {@link MiddlewareBase#activePoints()} declaration always wins; a {@code null}
 * declaration (the default) is auto-detected, per class and computed once, from
 * the hook methods the middleware's class actually implements — including
 * implementations inherited from superclasses.
 *
 * <p>Inference is a compatibility fallback for middlewares that do not
 * declare their points, not a statement of intent: a hook overridden only by a
 * pass-through base class still lights up its point, and an intentional
 * opt-out requires an explicit declaration. Inference results are structural
 * facts of a class (which of the hook methods it implements) and are therefore
 * stable; each class is computed once and cached.
 *
 * @author shanhongyu
 */
public final class ActivePointsResolver {

    /** Hook method of {@link MiddlewareBase} keyed by its extension point, resolved once at
     * class init. */
    private static final Map<MiddlewareBase.ExtensionPoint, Method> HOOKS_BY_POINT;

    /**
     * Per-class cache of inferred participation sets: {@link ClassValue} computes each entry
     * once, thread-safely, and keeps it only while the class is reachable (no classloader
     * retention).
     */
    private static final ClassValue<Set<MiddlewareBase.ExtensionPoint>> INFERRED_POINTS =
            new ClassValue<>() {
                @Override
                protected Set<MiddlewareBase.ExtensionPoint> computeValue(Class<?> type) {
                    return Collections.unmodifiableSet(inferPoints(type));
                }
            };

    static {
        Map<String, List<Method>> byName = new HashMap<>();
        for (Method method : MiddlewareBase.class.getMethods()) {
            byName.computeIfAbsent(method.getName(), name -> new ArrayList<>()).add(method);
        }
        Map<MiddlewareBase.ExtensionPoint, Method> hooks =
                new EnumMap<>(MiddlewareBase.ExtensionPoint.class);
        for (MiddlewareBase.ExtensionPoint point : MiddlewareBase.ExtensionPoint.values()) {
            List<Method> candidates = byName.get(point.hookMethod());
            if (candidates == null) {
                throw new IllegalStateException(
                        "Missing hook method for " + point + ": " + point.hookMethod());
            }
            if (candidates.size() > 1) {
                throw new IllegalStateException(
                        "Hook method for "
                                + point
                                + " must not be overloaded: "
                                + point.hookMethod());
            }
            hooks.put(point, candidates.get(0));
        }
        HOOKS_BY_POINT = Collections.unmodifiableMap(hooks);
    }

    /** Static utility; never instantiated. */
    private ActivePointsResolver() {}

    /**
     * Returns the middleware's participation set: the explicit {@link
     * MiddlewareBase#activePoints()} declaration when non-null, otherwise the
     * per-class inference of implemented hooks.
     *
     * @param middleware the middleware to resolve, never {@code null}
     * @return the participation set, never {@code null}
     */
    public static Set<MiddlewareBase.ExtensionPoint> resolve(MiddlewareBase middleware) {
        Objects.requireNonNull(middleware, "middleware");
        Set<MiddlewareBase.ExtensionPoint> declared = middleware.activePoints();
        return declared != null ? declared : INFERRED_POINTS.get(middleware.getClass());
    }

    /**
     * Infers the participation set of a middleware class: exactly the points
     * whose hook methods the class implements — its own overrides or
     * implementations inherited from superclasses.
     *
     * @param type the middleware class, never {@code null}
     * @return the inferred participation set, unmodifiable, never {@code null}
     */
    public static Set<MiddlewareBase.ExtensionPoint> infer(Class<? extends MiddlewareBase> type) {
        return INFERRED_POINTS.get(type);
    }

    /**
     * Returns the middlewares participating at the given extension point, filtered from the
     * candidates supplied by {@code middlewares} — the endpoint-dimension counterpart of
     * {@link #resolve(MiddlewareBase)}. Pass the middleware registry you want to query as
     * the supplier, e.g. {@code agent::getMiddlewares}; supplied order is preserved (stable
     * filter, onion order is the caller's concern).
     *
     * <p>This is a dynamic view: explicit declarations are re-read on every call (only the
     * auto-detection path is cached per class), so results reflect the current declarations.
     * An agent's own chain view is frozen at construction — for the chain an agent actually
     * runs, use the agent's {@code middlewaresAt(point)}.
     *
     * @param point the extension point to query, never {@code null}
     * @param middlewares supplies the candidate middlewares, never {@code null}, never
     *        returning {@code null}
     * @return the participants at {@code point} in supplied order, unmodifiable
     */
    public static List<MiddlewareBase> middlewaresAt(
            MiddlewareBase.ExtensionPoint point, Supplier<List<MiddlewareBase>> middlewares) {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(middlewares, "middlewares");
        List<MiddlewareBase> candidates =
                Objects.requireNonNull(middlewares.get(), "middlewares.get()");
        List<MiddlewareBase> participants = new ArrayList<>();
        for (MiddlewareBase mw : candidates) {
            if (resolve(mw).contains(point)) {
                participants.add(mw);
            }
        }
        return List.copyOf(participants);
    }

    /**
     * Groups middlewares per extension point by their resolved participation sets
     * (explicit declarations win, {@code null} is auto-detected from implemented hooks),
     * preserving the given list order within each point. Declarations are read during this
     * call — callers that need a stable view freeze the returned grouping (agents do so at
     * construction).
     *
     * @param middlewares the middlewares to group, never {@code null}
     * @return an unmodifiable map with unmodifiable participant lists; points without
     *         participants are absent
     */
    public static Map<MiddlewareBase.ExtensionPoint, List<MiddlewareBase>> groupByPoint(
            List<MiddlewareBase> middlewares) {
        Objects.requireNonNull(middlewares, "middlewares");
        Map<MiddlewareBase.ExtensionPoint, List<MiddlewareBase>> grouped =
                new EnumMap<>(MiddlewareBase.ExtensionPoint.class);
        for (MiddlewareBase mw : middlewares) {
            Set<MiddlewareBase.ExtensionPoint> active = resolve(mw);
            for (MiddlewareBase.ExtensionPoint point : MiddlewareBase.ExtensionPoint.values()) {
                if (active.contains(point)) {
                    grouped.computeIfAbsent(point, p -> new ArrayList<>()).add(mw);
                }
            }
        }
        grouped.replaceAll((point, participants) -> List.copyOf(participants));
        return Collections.unmodifiableMap(grouped);
    }

    /**
     * Computes the points whose hook methods {@code type} implements; runs at most once per
     * class, through {@link #INFERRED_POINTS}.
     */
    private static Set<MiddlewareBase.ExtensionPoint> inferPoints(Class<?> type) {
        EnumSet<MiddlewareBase.ExtensionPoint> active =
                EnumSet.noneOf(MiddlewareBase.ExtensionPoint.class);
        for (Map.Entry<MiddlewareBase.ExtensionPoint, Method> entry : HOOKS_BY_POINT.entrySet()) {
            if (implementsHook(type, entry.getValue())) {
                active.add(entry.getKey());
            }
        }
        return active;
    }

    /**
     * Returns whether {@code type} provides an implementation of {@code hook}: the most
     * specific public resolution of the method declares it in a type other than {@link
     * MiddlewareBase} itself — the type's own override, one inherited from a superclass, or
     * a sub-interface's redeclaration. Only a resolution falling back to {@code
     * MiddlewareBase}'s own pass-through default (no override anywhere in the hierarchy)
     * does not count.
     */
    private static boolean implementsHook(Class<?> type, Method hook) {
        try {
            Method impl = type.getMethod(hook.getName(), hook.getParameterTypes());
            return impl.getDeclaringClass() != MiddlewareBase.class;
        } catch (NoSuchMethodException ex) {
            // Every hook has a default implementation on the interface, so resolution
            // cannot miss; defensive only.
            return false;
        }
    }
}
