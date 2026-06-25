# Parking Lot — Java

JDK 25. No build tool; plain `javac`/`java`. Tests use TestNG (resolved from the
local Maven repo, same as the sibling `content-management-system` module).

## Layout

```
src/io/infrahack/parkinglot/
  enums/        VehicleType, SpotType, SpotStatus, TicketStatus, Role, GateType
  model/        Vehicle (+ Car/Motorcycle/Truck), ParkingSpot, ParkingLevel,
                ParkingLot, ParkingTicket, Gate, OccupancyReport, Money, User
  strategy/     SpotAssignmentStrategy (NearestFirst, BestFit),
                PricingStrategy (HourlyPricing)
  repository/   TicketRepository + InMemoryTicketRepository (optimistic CAS)
  service/      ParkingService, AdminService, PermissionService,
                MetricsCollector, AuditService
  factory/      ParkingLotFactory
  test/         ParkingServiceTest, PricingTest, ConcurrencyTest
  Main.java     end-to-end demo
docs/DESIGN.md  design deep-dive
scripts/test.sh build + run the demo + run the TestNG suite
```

## Run the demo

```bash
javac -d out $(find src -name '*.java' -not -path '*/test/*')
java -cp out io.infrahack.parkinglot.Main
```

## Run the tests

```bash
./scripts/test.sh
```

The script compiles everything against the TestNG jars in `~/.m2` and runs all
three test classes. Expected: `Total tests run: 15, Passes: 15, Failures: 0`.

## What to look at first

1. `model/ParkingLevel.java` — the lock-free per-spot-type free-list that makes
   concurrent claims correct without a global lock.
2. `service/ParkingService.java` — the entry/exit flow and the
   *save-CAS-before-release* ordering that frees a spot exactly once.
3. `test/ConcurrencyTest.java` — 200 drivers vs 50 spots, and a double-exit race.
