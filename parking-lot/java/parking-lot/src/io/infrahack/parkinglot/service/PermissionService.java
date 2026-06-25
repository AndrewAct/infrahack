package io.infrahack.parkinglot.service;

import io.infrahack.parkinglot.enums.Role;
import io.infrahack.parkinglot.exception.PermissionDeniedException;
import io.infrahack.parkinglot.model.User;

/** Role gate. Drivers park/exit; admins manage spots and read reports. */
public class PermissionService {
    public void requireDriver(User user) {
        if (user == null || !user.hasRole(Role.DRIVER)) {
            throw new PermissionDeniedException("Driver role required");
        }
    }

    public void requireAdmin(User user) {
        if (user == null || !user.hasRole(Role.ADMIN)) {
            throw new PermissionDeniedException("Admin role required");
        }
    }
}
