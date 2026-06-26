package io.infrahack.elevator.repository;

import io.infrahack.elevator.model.Building;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InMemoryBuildingRepository implements BuildingRepository {
    private final Map<String, Building> buildings = new HashMap<>();

    @Override
    public Optional<Building> findById(String id) {
        return Optional.ofNullable(buildings.get(id));
    }

    @Override
    public void save(Building building) {
        buildings.put(building.id(), building);
    }
}
