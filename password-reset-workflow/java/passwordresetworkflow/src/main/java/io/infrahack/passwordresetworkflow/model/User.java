package io.infrahack.passwordresetworkflow.model;

/** A user profile. {@code passwordHash} is {@code salt:sha256hex}, never the raw password. */
public record User(String email, String passwordHash) {}
