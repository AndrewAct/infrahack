package io.infrahack.contentmanagementsystem.service;

import io.infrahack.contentmanagementsystem.enums.Role;
import io.infrahack.contentmanagementsystem.exception.PermissionDeniedException;
import io.infrahack.contentmanagementsystem.model.User;

import static io.infrahack.contentmanagementsystem.enums.Role.EDITOR;
import static io.infrahack.contentmanagementsystem.enums.Role.PUBLISHER;

public class PermissionService {
    void requiredEdit(User user) {
        if (!user.hasRole(EDITOR)) {
            throw new PermissionDeniedException("User does not have permission to edit");
        }
    }

    void requiredPublish(User user) {
        if (!user.hasRole(PUBLISHER)) {
            throw new PermissionDeniedException("User does not have permission to publish");
        }
    }
}
