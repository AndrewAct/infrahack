package io.infrahack.moviewatchlistdb.exception;

import io.infrahack.moviewatchlistdb.model.WatchListId;

/** The target watch list does not exist. Maps to 404. */
public final class WatchListNotFoundException extends DomainException {

    public WatchListNotFoundException(WatchListId id) {
        super("watchlist_not_found", "Watch list not found: " + id);
    }
}
