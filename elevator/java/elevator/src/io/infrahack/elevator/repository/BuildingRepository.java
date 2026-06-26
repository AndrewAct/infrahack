package io.infrahack.elevator.repository;

import io.infrahack.elevator.model.Building;

import java.util.Optional;

public interface BuildingRepository {
    Optional<Building> findById(String id);
    void save(Building building);
}
