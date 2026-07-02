package io.infrahack.moviewatchlistspring.exception;

import io.infrahack.moviewatchlistspring.model.WatchListId;

/** The target watch list does not exist. Maps to 404. */
public final class WatchListNotFoundException extends DomainException {

    public WatchListNotFoundException(WatchListId id) {
        super("watchlist_not_found", "Watch list not found: " + id);
    }
}
