package io.github.xbta4224j.scanner.application

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.web.servlet.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.RequestMapping

/**
 * Replaces Spring Boot's whitelabel error page with a styled 4xx / 5xx view
 * that wears the dashboard chrome and steers the reviewer at the closest
 * useful next click (queue, live events, health).
 *
 * The template picked is `templates/error/4xx.html` for any client error and
 * `templates/error/5xx.html` for any server error, falling back to 5xx if
 * the status is missing or unknown. Both templates pull the same status /
 * error / path / message attributes so they stay in sync with the failure.
 */
@Controller
class AppErrorController : ErrorController {

    @RequestMapping("/error")
    fun handle(request: HttpServletRequest, model: Model): String {
        val statusCode = (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int)
            ?: HttpStatus.INTERNAL_SERVER_ERROR.value()
        val status = HttpStatus.resolve(statusCode) ?: HttpStatus.INTERNAL_SERVER_ERROR
        val path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as? String
        val message = request.getAttribute(RequestDispatcher.ERROR_MESSAGE) as? String

        model.addAttribute("status", status.value())
        model.addAttribute("error", status.reasonPhrase)
        model.addAttribute("path", path ?: "")
        model.addAttribute("message", message ?: "")

        return if (status.is5xxServerError) "error/5xx" else "error/4xx"
    }
}
