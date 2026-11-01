package com.sakura.interview.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

/**
 * 全局异常到统一信封的出口：业务异常按自带状态码返回；
 * 上传超限映射 413；未知异常收口为 500 且不外泄堆栈。
 * 未匹配页面路由（NoResourceFoundException）转发 index.html 交前端路由接管。
 */
@ControllerAdvice
public final class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final boolean HAS_FRONTEND =
            new ClassPathResource("static/index.html").exists();

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiEnvelope<Void>> apiException(ApiException e) {
        return ResponseEntity.status(e.httpStatus())
                .body(new ApiEnvelope<>(e.httpStatus(), e.getMessage(), null));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiEnvelope<Void>> uploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413)
                .body(new ApiEnvelope<>(413, "上传文件超过 5MB 上限", null));
    }

    /** 页面路由兜底：非 API 路径的未匹配资源转 index.html；API 路径回 404 信封。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public Object spaOrNotFound(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
        if (HAS_FRONTEND && !uri.startsWith("/api") && !uri.startsWith("/actuator")) {
            response.setStatus(HttpServletResponse.SC_OK);
            return new ModelAndView("forward:/index.html");
        }
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":404,\"message\":\"接口不存在\",\"data\":null}");
        return null;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiEnvelope<Void>> unexpected(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(500)
                .body(new ApiEnvelope<>(500, "服务器内部错误", null));
    }
}
