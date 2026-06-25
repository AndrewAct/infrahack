package io.infrahack.parkinglot.service;

import io.infrahack.parkinglot.model.OccupancyReport;
import io.infrahack.parkinglot.model.ParkingLot;
import io.infrahack.parkinglot.model.User;

/**
 * Admin-facing operations: take spots out of rotation for maintenance and read
 * occupancy. Every call is permission-gated and audited so operational changes
 * to capacity are attributable.
 */
public class AdminService {
    private final ParkingLot lot;
    private final PermissionService permissionService;
    private final MetricsCollector metrics;
    private final AuditService audit;

    public AdminService(ParkingLot lot,
                        PermissionService permissionService,
                        MetricsCollector metrics,
                        AuditService audit) {
        this.lot = lot;
        this.permissionService = permissionService;
        this.metrics = metrics;
        this.audit = audit;
    }

    public boolean takeSpotOutOfService(User admin, int level, String spotId) {
        permissionService.requireAdmin(admin);
        boolean changed = lot.takeSpotOutOfService(level, spotId);
        if (changed) {
            metrics.increment("parking.spot.out_of_service");
            audit.record(admin, "SPOT_OUT_OF_SERVICE", spotId);
        }
        return changed;
    }

    public boolean returnSpotToService(User admin, int level, String spotId) {
        permissionService.requireAdmin(admin);
        boolean changed = lot.returnSpotToService(level, spotId);
        if (changed) {
            metrics.increment("parking.spot.returned_to_service");
            audit.record(admin, "SPOT_RETURNED_TO_SERVICE", spotId);
        }
        return changed;
    }

    public OccupancyReport occupancy(User admin) {
        permissionService.requireAdmin(admin);
        return lot.occupancy();
    }
}
