package io.infrahack.ridesharedispatch.domain.exception;

/** Any "no such resource" case: unknown driver, dispatch request, offer, or assignment id. */
public final class NotFoundException extends DomainException {

    public NotFoundException(String resource, Object id) {
        super("%s not found: %s".formatted(resource, id));
    }

    @Override
    public String code() {
        return "not_found";
    }
}
