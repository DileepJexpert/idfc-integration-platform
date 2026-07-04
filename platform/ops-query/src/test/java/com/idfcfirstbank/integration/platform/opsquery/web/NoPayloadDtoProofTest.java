package com.idfcfirstbank.integration.platform.opsquery.web;

import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.fail;

/**
 * THE PII PROOF (B.3, D13): the ops wire DTO tree is walked reflectively and
 * every reachable field must be an id-shaped scalar or a nested allow-listed
 * record — a Map, a JsonNode, an Object, a byte[] or any field NAMED like a
 * payload cannot exist. Anyone adding such a field later fails THIS test, not
 * a code review.
 */
class NoPayloadDtoProofTest {

    private static final Set<Class<?>> ALLOWED_SCALARS = Set.of(
            String.class, Instant.class,
            int.class, long.class, boolean.class,
            Integer.class, Long.class, Boolean.class);

    private static final Set<String> FORBIDDEN_NAMES = Set.of(
            "payload", "context", "collectedresults", "body", "data", "result", "envelope");

    @Test
    void theOpsDtoTreeHasNoFieldAPayloadCouldRideIn() {
        walk(OpsDtos.PageDto.class, "PageDto");
        walk(OpsDtos.RunDetailDto.class, "RunDetailDto");
        walk(OpsDtos.MetricsDto.class, "MetricsDto");
        walk(OpsDtos.ErrorDto.class, "ErrorDto");
    }

    private void walk(Class<?> record, String path) {
        if (!record.isRecord()) {
            fail("%s: %s is not a record — only records may appear on the ops wire"
                    .formatted(path, record.getName()));
        }
        for (RecordComponent component : record.getRecordComponents()) {
            String where = path + "." + component.getName();
            if (FORBIDDEN_NAMES.contains(component.getName().toLowerCase(Locale.ROOT))) {
                fail(where + ": payload-shaped field NAME is forbidden on the ops wire");
            }
            check(component.getGenericType(), where);
        }
    }

    private void check(Type type, String where) {
        if (type instanceof Class<?> cls) {
            if (ALLOWED_SCALARS.contains(cls)) {
                return;
            }
            if (cls.isRecord() && cls.getName().startsWith("com.idfcfirstbank.integration.platform.opsquery")) {
                walk(cls, where);
                return;
            }
            fail(where + ": type " + cls.getName() + " is not allow-listed for the ops wire"
                    + " (no Map/Object/JsonNode/byte[]/entities — ids and timestamps only)");
        } else if (type instanceof ParameterizedType p) {
            if (p.getRawType() == List.class) {
                check(p.getActualTypeArguments()[0], where + "[]");
                return;
            }
            fail(where + ": parameterized type " + p + " is not allow-listed (only List<...>)");
        } else {
            fail(where + ": unsupported generic shape " + type);
        }
    }
}
