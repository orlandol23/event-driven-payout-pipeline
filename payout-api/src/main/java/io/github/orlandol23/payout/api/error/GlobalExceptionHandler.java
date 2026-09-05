package io.github.orlandol23.payout.api.error;

import io.github.orlandol23.payout.api.correlation.CorrelationIdProvider;
import io.github.orlandol23.payout.api.payout.IdempotencyKeyReusedException;
import io.github.orlandol23.payout.api.payout.PayoutNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Turns every failure into an RFC 7807 {@code application/problem+json} response.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} rather than starting from
 * scratch, which means the framework's own failures (405, 406, 415, unknown
 * path) already come back as problem documents. The overrides below only change
 * the ones where the default detail is too vague to act on.
 *
 * <p>Chosen over {@code spring.mvc.problemdetails.enabled=true}. That property
 * gets the same envelope with no code, but leaves no place to attach the
 * correlation id or the per-field breakdown, and the two mechanisms overlap if
 * both are on.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Extension member naming the request that failed, for support tickets. */
    private static final String CORRELATION_ID_PROPERTY = "correlationId";
    private static final String TIMESTAMP_PROPERTY = "timestamp";
    private static final String ERRORS_PROPERTY = "errors";

    private final CorrelationIdProvider correlationIdProvider;
    private final Clock clock;

    public GlobalExceptionHandler(CorrelationIdProvider correlationIdProvider, Clock clock) {
        this.correlationIdProvider = correlationIdProvider;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------
    // Domain failures
    // ---------------------------------------------------------------------

    @ExceptionHandler(PayoutNotFoundException.class)
    public ProblemDetail handlePayoutNotFound(PayoutNotFoundException exception,
                                              HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND,
                ProblemTypes.PAYOUT_NOT_FOUND,
                "Payout not found",
                exception.getMessage(),
                request.getRequestURI());
    }

    /**
     * The caller reused an {@code Idempotency-Key} for a different request.
     *
     * <p>422 rather than 409. The request is syntactically fine and the server
     * understood it perfectly; it refuses to act on it because the key already
     * stands for something else. 409 would invite a retry, and this is the one
     * failure a retry can never fix: the same key with the same new body fails
     * identically forever. Changing the key, or the body, is the only way out.
     *
     * <p>Neither the key nor the winning payout id is echoed back. The caller
     * already knows the key it sent, and the payout it names belongs to the
     * earlier request; handing its id to whoever sent the second one turns a
     * guessed key into a way to discover payout ids. Both are logged instead.
     */
    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ProblemDetail handleIdempotencyKeyReused(IdempotencyKeyReusedException exception,
                                                    HttpServletRequest request) {
        log.warn("Idempotency key {} reused with a different request; it already created payout {}",
                exception.getIdempotencyKey(), exception.getExistingPayoutId());
        return problem(HttpStatus.UNPROCESSABLE_ENTITY,
                ProblemTypes.IDEMPOTENCY_MISMATCH,
                "Idempotency key reused",
                "This Idempotency-Key was already used for a different request. "
                        + "A key may only be replayed with the same amount and currency.",
                request.getRequestURI());
    }

    /**
     * A constraint violation that reached this far is not the idempotency race,
     * which the service already recovers from. Something else collided.
     *
     * <p>The exception message is logged but never returned: it contains the
     * constraint name and often the offending SQL, which hands an attacker a map
     * of the schema.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException exception,
                                                      HttpServletRequest request) {
        log.warn("Constraint violation on {}", request.getRequestURI(), exception);
        return problem(HttpStatus.CONFLICT,
                ProblemTypes.CONFLICT,
                "Conflict",
                "The request conflicts with the current state of the resource.",
                request.getRequestURI());
    }

    /**
     * Last resort. Anything reaching here is a bug, so it is logged at error with
     * the stack trace and answered with a body that says nothing useful to an
     * attacker beyond the correlation id needed to find that log line.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR,
                ProblemTypes.INTERNAL_ERROR,
                "Internal server error",
                "The request could not be processed. Quote the correlation id when reporting this.",
                request.getRequestURI());
    }

    // ---------------------------------------------------------------------
    // Framework failures worth reshaping
    // ---------------------------------------------------------------------

    /** Body level Bean Validation: {@code @Valid} on the request record failed. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<ApiFieldError> errors = exception.getBindingResult().getAllErrors().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                ProblemTypes.VALIDATION_FAILED,
                "Validation failed",
                "The request body failed validation. See the errors field.",
                instanceOf(request));
        problem.setProperty(ERRORS_PROPERTY, errors);
        return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Parameter level Bean Validation, for example {@code @Size} on the
     * {@code Idempotency-Key} header. Spring validates these without any
     * {@code @Validated} on the controller since Framework 6.1.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
                                                                           HttpHeaders headers,
                                                                           HttpStatusCode status,
                                                                           WebRequest request) {
        List<ApiFieldError> errors = exception.getAllErrors().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                ProblemTypes.VALIDATION_FAILED,
                "Validation failed",
                "A request parameter failed validation. See the errors field.",
                instanceOf(request));
        problem.setProperty(ERRORS_PROPERTY, errors);
        return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Unparseable body: missing, truncated, or a value Jackson could not bind
     * (a string where the amount should be, for example).
     *
     * <p>The Jackson message is dropped rather than forwarded. It leaks internal
     * class names and reads as a stack trace to whoever called the API.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        log.debug("Unreadable request body", exception);
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                ProblemTypes.MALFORMED_REQUEST,
                "Malformed request",
                "The request body is missing or is not valid JSON matching the expected schema.",
                instanceOf(request));
        return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** A path variable or query parameter that would not convert, such as a non-UUID id. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception,
                                                        HttpHeaders headers,
                                                        HttpStatusCode status,
                                                        WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                ProblemTypes.MALFORMED_REQUEST,
                "Malformed request",
                "'%s' is not a valid value for %s.".formatted(
                        exception.getValue(), exception.getPropertyName()),
                instanceOf(request));
        return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Single funnel every response from the parent class passes through.
     *
     * <p>Overriding it here is what guarantees the framework's own problems, the
     * 405s and 415s nobody wrote a handler for, still carry a correlation id and
     * a timestamp.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body,
                                                          HttpHeaders headers,
                                                          HttpStatusCode statusCode,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            enrich(problem, instanceOf(request));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private ProblemDetail problem(HttpStatus status, URI type, String title, String detail, String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        enrich(problem, instance);
        return problem;
    }

    private void enrich(ProblemDetail problem, String instance) {
        if (problem.getProperties() == null || !problem.getProperties().containsKey(CORRELATION_ID_PROPERTY)) {
            problem.setProperty(CORRELATION_ID_PROPERTY, correlationIdProvider.current());
            problem.setProperty(TIMESTAMP_PROPERTY, Instant.now(clock).toString());
        }
        if (problem.getInstance() == null && instance != null) {
            problem.setInstance(URI.create(instance));
        }
    }

    private static String instanceOf(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : null;
    }

    /**
     * Names the offending field.
     *
     * <p>{@link FieldError} knows its field. A class level constraint does not
     * have one, so the object name is used instead of inventing a fake path.
     */
    private static ApiFieldError toFieldError(ObjectError error) {
        String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
        return new ApiFieldError(field, error.getDefaultMessage());
    }

    /**
     * Parameter level errors carry no field name at all, only resolvable message
     * codes. The last code is the most specific one Bean Validation produced.
     */
    private static ApiFieldError toFieldError(MessageSourceResolvable error) {
        if (error instanceof ObjectError objectError) {
            return toFieldError(objectError);
        }
        String[] codes = error.getCodes();
        String field = codes != null && codes.length > 0 ? codes[codes.length - 1] : "request";
        return new ApiFieldError(field, error.getDefaultMessage());
    }
}
