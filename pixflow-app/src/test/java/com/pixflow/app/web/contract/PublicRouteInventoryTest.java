package com.pixflow.app.web.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

class PublicRouteInventoryTest {
    private static final Set<String> EXPECTED = Set.of(
            "POST /api/auth/login", "POST /api/auth/refresh", "POST /api/auth/logout", "GET /api/auth/me",
            "POST /api/conversations", "GET /api/conversations", "GET /api/conversations/{conversationId}",
            "DELETE /api/conversations/{conversationId}",
            "GET /api/conversations/{conversationId}/messages",
            "POST /api/conversations/{conversationId}/messages",
            "POST /api/conversations/{conversationId}/turns/stop",
            "POST /api/conversations/{conversationId}/proposals/{proposalId}/confirm",
            "POST /api/conversations/{conversationId}/proposals/{proposalId}/reject",
            "GET /api/asset-references", "POST /api/files/packages/init",
            "GET /api/files/packages/sessions/{uploadId}",
            "PUT /api/files/packages/sessions/{uploadId}/chunks/{index}",
            "POST /api/files/packages/sessions/{uploadId}/complete",
            "DELETE /api/files/packages/sessions/{uploadId}",
            "POST /api/files/packages/{packageId}/cancel-extraction",
            "GET /api/files/packages", "GET /api/files/packages/{packageId}",
            "GET /api/files/packages/{packageId}/skus", "GET /api/files/packages/{packageId}/images",
            "GET /api/files/packages/{packageId}/images/{imageId}", "GET /api/files/images",
            "GET /api/files/packages/{packageId}/errors", "PATCH /api/files/packages/{packageId}",
            "PATCH /api/files/packages/{packageId}/images/{imageId}", "DELETE /api/files/packages/{packageId}",
            "DELETE /api/files/packages/{packageId}/images/{imageId}",
            "GET /api/vision/packages/{packageId}/skus/{skuId}/facts",
            "PUT /api/vision/packages/{packageId}/skus/{skuId}/facts",
            "POST /api/vision/packages/{packageId}/skus/{skuId}/reanalyze",
            "GET /api/outputs/conversations", "GET /api/outputs/conversations/{conversationId}/tasks",
            "GET /api/outputs/tasks/{taskId}/images", "PATCH /api/outputs/images/{imageId}",
            "DELETE /api/outputs/images/{imageId}", "GET /api/activities",
            "GET /api/activities/{activityId}", "POST /api/activities/{activityId}/cancel",
            "POST /api/activities/{activityId}/retry-failed", "DELETE /api/activities/{activityId}",
            "POST /api/downloads/bundle");

    @Test
    void publicRoutesMatchTheAuthoritativeWireContractExactly() throws Exception {
        assertThat(discoverRoutes()).containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    private static Set<String> discoverRoutes() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> routes = new LinkedHashSet<>();
        for (var candidate : scanner.findCandidateComponents("com.pixflow.app.web")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            String prefix = firstPath(controller.getAnnotation(RequestMapping.class));
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String path = prefix + firstPath(mapping);
                Arrays.stream(mapping.method())
                        .map(RequestMethod::name)
                        .map(httpMethod -> httpMethod + " " + path)
                        .forEach(routes::add);
            }
        }
        return routes;
    }

    private static String firstPath(RequestMapping mapping) {
        if (mapping == null) {
            return "";
        }
        if (mapping.path().length > 0) {
            return mapping.path()[0];
        }
        return mapping.value().length == 0 ? "" : mapping.value()[0];
    }
}
