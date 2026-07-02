package io.infrahack.moviewatchlistdb.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A movie identifier as a value type.
 *
 * <p>Why a {@code record} wrapping a {@link UUID} instead of a bare {@code String}/{@code UUID}:
 * <ul>
 *   <li><b>Value equality for free.</b> Records generate {@code equals}/{@code hashCode} from the
 *       component, so two {@code MovieId}s with the same UUID are equal. This is exactly the bug the
 *       exercise targets: duplicate detection must compare identifiers <i>by value</i>, never by
 *       object reference ({@code ==}). Put these in a {@code Set} and dedup just works.</li>
 *   <li><b>Type safety.</b> A method that takes a {@code MovieId} cannot be handed a
 *       {@link WatchListId} by mistake — the compiler rejects it.</li>
 * </ul>
 */
public record MovieId(UUID value) {

    public MovieId {
        Objects.requireNonNull(value, "movie id value must not be null");
    }

    /** Parse from the raw string in a request. Throws {@link IllegalArgumentException} if malformed
     *  (the web layer maps that to 400 Bad Request). */
    public static MovieId of(String raw) {
        return new MovieId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
