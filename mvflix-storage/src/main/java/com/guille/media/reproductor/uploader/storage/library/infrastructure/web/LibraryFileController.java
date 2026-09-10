package com.guille.media.reproductor.uploader.storage.library.infrastructure.web;

import com.guille.media.reproductor.uploader.storage.library.application.LibraryService;
import com.guille.media.reproductor.uploader.storage.library.application.port.LibraryContentResolver;
import com.guille.media.reproductor.uploader.storage.library.application.port.LibraryFileHandle;

import lombok.extern.slf4j.Slf4j;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Playback LOCAL de una biblioteca del operador: sirve el archivo desde el
 * filesystem con soporte HTTP Range (206). El navegador consume esto
 * directamente (o via el proxy del BFF).
 */
@Slf4j
@Tag(name = "Bibliotecas · Archivos", description = "Servir bytes locales con soporte HTTP Range")
@RestController
@RequestMapping(value = "/api/v1/movie/storage")
public class LibraryFileController {

    private static final int BUFFER_SIZE = 64 * 1024;

    private final LibraryService libraryService;
    private final LibraryContentResolver fileResolver;

    public LibraryFileController(LibraryService libraryService, LibraryContentResolver fileResolver) {
        this.libraryService = libraryService;
        this.fileResolver = fileResolver;
    }

    @GetMapping(value = "/libraries/{libraryId}/files/**")
    public Mono<ResponseEntity<Flux<DataBuffer>>> stream(
            @PathVariable Long libraryId, ServerHttpRequest request) {
        String relativePath = this.relativePath(request);
        if (relativePath == null || relativePath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "relative path vacio");
        }
        return this.libraryService
                .findAccessibleLibrary(libraryId)
                .flatMap(library -> this.fileResolver.resolve(library, relativePath))
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found")))
                .map(handle -> this.response(handle, request));
    }

    @GetMapping(value = "/playback/libraries/{libraryId}/files/**")
    public Mono<ResponseEntity<Flux<DataBuffer>>> playbackStream(
            @PathVariable Long libraryId, ServerHttpRequest request) {
        String relativePath = this.relativePath(request, "/api/v1/movie/storage/playback/libraries/");
        if (relativePath == null || relativePath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "relative path vacio");
        }
        return this.libraryService.findPlaybackLibrary(libraryId)
                .flatMap(library -> this.fileResolver.resolve(library, relativePath))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found")))
                .map(handle -> this.response(handle, request));
    }

    private ResponseEntity<Flux<DataBuffer>> response(
            LibraryFileHandle handle, ServerHttpRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(handle.mimeType()));
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");

        List<HttpRange> ranges = request.getHeaders().getRange();
        if (ranges.isEmpty()) {
            headers.setContentLength(handle.size());
            return ResponseEntity.ok().headers(headers).body(this.fullBody(handle));
        }

        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(handle.size());
        long end = range.getRangeEnd(handle.size());
        long length = end - start + 1;

        if (start >= handle.size() || length < 0) {
            HttpHeaders unsatisfiable = new HttpHeaders();
            unsatisfiable.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            unsatisfiable.set(HttpHeaders.CONTENT_RANGE, "bytes */" + handle.size());
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .headers(unsatisfiable)
                    .build();
        }

        headers.set(HttpHeaders.CONTENT_RANGE,
                "bytes " + start + "-" + end + "/" + handle.size());
        headers.setContentLength(length);
        log.info("stream LOCAL: library={} path={} range=bytes {}-{}",
                handle.relativePath(), start, end);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .headers(headers)
                .body(this.rangeBody(handle.absolutePath(), start, length));
    }

    private Flux<DataBuffer> fullBody(LibraryFileHandle handle) {
        return DataBufferUtils.readInputStream(
                () -> Files.newInputStream(handle.absolutePath()),
                org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance,
                BUFFER_SIZE);
    }

    private Flux<DataBuffer> rangeBody(Path path, long start, long length) {
        return DataBufferUtils.readInputStream(
                () -> {
                    InputStream in = Files.newInputStream(path);
                    long skipped = in.skip(start);
                    if (skipped != start) {
                        in.close();
                        throw new IOException("No se pudo posicionar en el rango solicitado");
                    }
                    return new BoundedInputStream(in, length);
                },
                org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance,
                BUFFER_SIZE);
    }

    private String relativePath(ServerHttpRequest request) {
        return relativePath(request, "/api/v1/movie/storage/libraries/");
    }

    private String relativePath(ServerHttpRequest request, String prefix) {
        String path = request.getPath().value();
        int marker = path.indexOf(prefix);
        if (marker < 0) {
            return null;
        }
        String rest = path.substring(marker + prefix.length());
        int afterId = rest.indexOf('/');
        int afterFiles = afterId < 0 ? -1 : rest.indexOf('/', afterId + 1);
        if (afterFiles < 0) {
            return "";
        }
        return org.springframework.web.util.UriUtils.decode(
                rest.substring(afterFiles + 1), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static final class BoundedInputStream extends java.io.FilterInputStream {

        private long remaining;

        BoundedInputStream(InputStream in, long length) {
            super(in);
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (this.remaining <= 0) {
                return -1;
            }
            int read = super.read();
            if (read >= 0) {
                this.remaining--;
            }
            return read;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (this.remaining <= 0) {
                return -1;
            }
            int max = (int) Math.min(length, this.remaining);
            int read = super.read(buffer, offset, max);
            if (read > 0) {
                this.remaining -= read;
            }
            return read;
        }
    }
}
