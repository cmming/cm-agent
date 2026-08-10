package com.cmagent.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** 为每个 HTTP 请求建立可回传给调用方的关联编号。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    public static final String ERROR_ID_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".errorId";
    public static final String ERROR_ID_HEADER = "X-Request-Id";
    private static final Pattern VALID_ERROR_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String errorId = resolveErrorId(request.getHeader(ERROR_ID_HEADER));
        request.setAttribute(ERROR_ID_ATTRIBUTE, errorId);
        response.setHeader(ERROR_ID_HEADER, errorId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("errorId", errorId)) {
            filterChain.doFilter(request, response);
        }
    }

    /**
     * 读取当前请求的关联编号；未经过过滤器的测试或特殊入口会自动补齐。
     *
     * @param request 当前 HTTP 请求
     * @return 可安全写入日志和响应的错误编号
     */
    public static String errorIdOf(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(ERROR_ID_ATTRIBUTE);
        if (value instanceof String errorId && VALID_ERROR_ID.matcher(errorId).matches()) {
            return errorId;
        }
        String errorId = UUID.randomUUID().toString();
        if (request != null) {
            request.setAttribute(ERROR_ID_ATTRIBUTE, errorId);
        }
        return errorId;
    }

    private String resolveErrorId(String candidate) {
        if (candidate != null && VALID_ERROR_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
