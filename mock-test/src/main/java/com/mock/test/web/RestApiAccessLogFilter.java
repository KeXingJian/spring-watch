package com.mock.test.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@Order(1)
public class RestApiAccessLogFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!uri.startsWith(API_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String method = request.getMethod();
        String query = request.getQueryString();
        long start = System.currentTimeMillis();

        log.info("[kxj: REST API 入口] method={} uri={}{}", method, uri, query == null ? "" : "?" + query);

        try {
            chain.doFilter(request, response);
        } finally {
            long cost = System.currentTimeMillis() - start;
            int status = response.getStatus();
            if (status >= 500) {
                log.error("[kxj: REST API 出口-服务异常] method={} uri={} status={} cost={}ms", method, uri, status, cost);
            } else if (status >= 400) {
                log.warn("[kxj: REST API 出口-业务异常] method={} uri={} status={} cost={}ms", method, uri, status, cost);
            } else {
                log.info("[kxj: REST API 出口-成功] method={} uri={} status={} cost={}ms", method, uri, status, cost);
            }
        }
    }
}
