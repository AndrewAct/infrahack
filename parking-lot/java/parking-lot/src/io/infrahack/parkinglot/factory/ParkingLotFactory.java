package io.infrahack.parkinglot.factory;

import io.infrahack.parkinglot.enums.GateType;
import io.infrahack.parkinglot.enums.SpotType;
import io.infrahack.parkinglot.model.Gate;
import io.infrahack.parkinglot.model.ParkingLevel;
import io.infrahack.parkinglot.model.ParkingLot;
import io.infrahack.parkinglot.model.ParkingSpot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ParkingLot} from a per-level spot mix, so callers describe
 * capacity declaratively instead of newing up hundreds of spots. Spot ids are
 * deterministic ("L{level}-{TYPE}-{n}") to make logs and tests readable.
 */
public class ParkingLotFactory {

    /**
     * @param spotMixPerLevel ordered list, one entry per level (level 1 first =
     *                        nearest), each mapping spot type to count.
     */
    public ParkingLot create(String name, List<Map<SpotType, Integer>> spotMixPerLevel) {
        List<ParkingLevel> levels = new ArrayList<>();
        for (int i = 0; i < spotMixPerLevel.size(); i++) {
            int levelNumber = i + 1;
            ParkingLevel level = new ParkingLevel(levelNumber);
            Map<SpotType, Integer> mix = spotMixPerLevel.get(i);
            for (Map.Entry<SpotType, Integer> entry : mix.entrySet()) {
                SpotType type = entry.getKey();
                for (int n = 1; n <= entry.getValue(); n++) {
                    level.addSpot(new ParkingSpot("L" + levelNumber + "-" + type + "-" + n, type, levelNumber));
                }
            }
            levels.add(level);
        }
        List<Gate> gates = List.of(
                new Gate("entry-1", GateType.ENTRY, 1),
                new Gate("exit-1", GateType.EXIT, 1)
        );
        return new ParkingLot(name, levels, gates);
    }
}
