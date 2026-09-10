package com.guille.media.reproductor.uploader.storage.library.application;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import com.guille.media.reproductor.uploader.storage.shared.security.UserProvider;
import com.guille.media.reproductor.uploader.storage.library.domain.model.MediaLibrary;
import com.guille.media.reproductor.uploader.storage.library.domain.port.LibraryScanner;
import com.guille.media.reproductor.uploader.storage.library.domain.port.MediaLibraryRepository;
import com.guille.media.reproductor.uploader.storage.library.domain.model.DiscoveredFile;
import com.guille.media.reproductor.uploader.storage.shared.error.EntityNotFound;
import com.guille.media.reproductor.uploader.storage.library.domain.port.ScanCancellation;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Bibliotecas del media server: listado y escaneo. El scanner solo descubre
 * archivos; la identificacion y el catalogo viven en el servicio de movies.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LibraryService {

    private final MediaLibraryRepository libraryRepository;
    private final LibraryScanner libraryScanner;
    private final UserProvider userProvider;
    private final ScanCancellation cancellationRegistry;

    public Flux<MediaLibrary> listLibraries() {
        return this.userProvider
                .getAuthenticatedUser()
                .flatMapMany(user ->
                        this.libraryRepository.findAllAccessibleTo(user.subject()));
    }

    public Mono<MediaLibrary> findAccessibleLibrary(Long libraryId) {
        return this.userProvider
                .getAuthenticatedUser()
                .flatMap(user ->
                        this.libraryRepository
                                .findById(libraryId)
                                .filter(library -> library.isAccessibleTo(user.subject()))
                                .switchIfEmpty(
                                        Mono.error(new EntityNotFound("Library not found: " + libraryId))));
    }

    /** Playback is authorized by the caller's storage.stream scope, not library ownership. */
    public Mono<MediaLibrary> findPlaybackLibrary(Long libraryId) {
        return this.libraryRepository.findById(libraryId)
                .filter(MediaLibrary::isEnabled)
                .switchIfEmpty(Mono.error(new EntityNotFound("Library not found: " + libraryId)));
    }

    public Flux<DiscoveredFile> scanLibrary(Long libraryId) {
        return this.findAccessibleLibrary(libraryId)
                .flatMapMany(
                        library -> this.libraryScanner
                                .scan(library.getRootPath())
                                .doOnComplete(
                                        () -> log.info(
                                                "Library scanned: id={} root={}",
                                                libraryId, library.getRootPath())));
    }

    public Mono<Void> cancelScan(Long libraryId) {
        return this.findAccessibleLibrary(libraryId)
                .doOnNext(library ->
                        this.cancellationRegistry.cancel(library.getRootPath()))
                .then();
    }
}
