package webpresenter.server.library;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps library errors to RFC 9457 problem responses. */
@RestControllerAdvice(assignableTypes = MediaController.class)
class LibraryExceptionHandler {

    @ExceptionHandler(MediaLibraryService.MediaNotFoundException.class)
    ProblemDetail notFound(MediaLibraryService.MediaNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(MediaLibraryService.UnsupportedMediaException.class)
    ProblemDetail unsupported(MediaLibraryService.UnsupportedMediaException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }

    @ExceptionHandler(MediaProbe.MediaProbeException.class)
    ProblemDetail probeFailed(MediaProbe.MediaProbeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
}
