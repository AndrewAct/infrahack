package io.infrahack.parkinglot.model;

import io.infrahack.parkinglot.enums.Role;

import java.util.Set;

public record User(String id, String name, Set<Role> roles) {
    public boolean hasRole(Role role) {
        return roles.contains(role);
    }
}
