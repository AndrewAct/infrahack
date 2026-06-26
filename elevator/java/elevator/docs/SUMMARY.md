# Elevator System Summary

## Goal

This project models a small elevator system in an object-oriented, interview-friendly style. It focuses on the core behavior of a real elevator:

- handling external hall button requests,
- handling internal floor button requests,
- opening and closing doors,
- tracking pending stops,
- enforcing safety rules such as emergency mode and overload prevention,
- keeping the design simple enough to implement within a 60-minute OOD interview.

The current implementation intentionally models a single default elevator inside a building. This keeps the base design focused, while still leaving room to add a dispatcher later for multi-elevator buildings.

## Main Objects

### Building

`Building` represents the physical environment:

- building id,
- valid floor range,
- elevators in the building,
- hall panels on each floor.

In the current version, `Building.defaultElevator()` returns the first elevator. This is enough for a single-elevator building. For a multi-elevator system, this method should be replaced by a dispatcher strategy.

### Elevator

`Elevator` owns the core elevator state machine:

- current floor,
- current direction,
- current status,
- door state,
- current load,
- max load,
- pending stops.

It also enforces safety invariants:

- the elevator cannot move while the door is open,
- the elevator cannot move while overloaded,
- emergency mode clears pending stops and opens the door,
- an overloaded elevator opens the door and stops moving.

### Door

`Door` is intentionally small. It only tracks whether the door is open or closed. The rule for whether the door is allowed to open or close belongs to `Elevator`, because that rule depends on elevator status and load.

### Button

`Button` represents a physical button. It stores:

- label,
- button type,
- whether it is currently selected/lit.

The selected/lit state models the real-world behavior where a pressed elevator button lights up until the request is served or cleared.

### HallPanel

`HallPanel` represents the panel outside the elevator on a floor. It contains:

- an up button,
- a down button.

Pressing one of these buttons creates a `HallRequest`, which records both the floor and desired direction.

### CarPanel

`CarPanel` represents the panel inside the elevator. It contains:

- floor buttons,
- open door button,
- close door button,
- emergency button.

Pressing a floor button creates a `CarRequest`, which represents one destination button press. Multiple passengers selecting multiple floors are represented as multiple `CarRequest` objects, which are aggregated by the elevator into its pending `stops` set.

## Request Model

The system separates two request types:

### HallRequest

Created when a passenger outside the elevator presses up or down.

Example:

```java
new HallRequest(4, Direction.UP);
```

This means someone on floor 4 wants to go up.

### CarRequest

Created when a passenger inside the elevator presses a destination floor.

Example:

```java
new CarRequest(8);
```

This means someone inside the elevator wants to stop at floor 8.

A `CarRequest` has one destination floor because it models one button press. The elevator can still visit many floors because it aggregates many requests into:

```java
TreeSet<Integer> stops;
```

## Service Layer

`ElevatorSystemService` is the use-case layer. It coordinates:

- loading the building from the repository,
- pressing hall buttons,
- pressing car floor buttons,
- pressing open/close door buttons,
- pressing emergency,
- updating load,
- advancing the simulation with `tick()`,
- saving the updated building,
- recording audit events,
- incrementing metrics.

This mirrors the content management system style where the service layer coordinates repository, audit, metrics, and domain objects.

## Repository Layer

`BuildingRepository` abstracts persistence.

`InMemoryBuildingRepository` stores buildings in a map and is useful for demos and tests. A real implementation could later be backed by a database without changing the service layer.

## Important Flows

### Hall Button Flow

1. User presses an outside button on a floor.
2. `ElevatorSystemService.pressHallButton(floor, direction)` is called.
3. The building finds the `HallPanel` for that floor.
4. The panel creates a `HallRequest`.
5. The default elevator adds the request floor into pending stops.
6. Audit and metrics are updated.

### Car Button Flow

1. Passenger enters the elevator.
2. Passenger presses a floor button inside the car.
3. `ElevatorSystemService.pressFloorButton(floor)` is called.
4. The `CarPanel` creates a `CarRequest`.
5. The elevator adds that floor into pending stops.
6. Audit and metrics are updated.

### Movement Flow

1. `tick()` advances the system by one unit of time.
2. If the elevator is in emergency mode, it does not move.
3. If the elevator is overloaded, it opens the door and does not move.
4. If the door is open, the elevator closes it first.
5. If there are no pending stops, the elevator becomes idle.
6. Otherwise, it chooses the next stop and moves one floor toward it.
7. If it reaches a requested floor, it opens the door and clears that stop.

### Emergency Flow

1. Emergency button is pressed.
2. Pending stops are cleared.
3. Direction becomes `IDLE`.
4. Status becomes `EMERGENCY`.
5. Door opens.
6. Emergency metric is incremented.

### Overload Flow

1. Load is updated through `updateLoad(loadKg)`.
2. If current load reaches or exceeds max load, the elevator is overloaded.
3. The door opens.
4. Direction becomes `IDLE`.
5. Elevator does not move until load is reduced.

## OOD / SOLID Notes

### Single Responsibility Principle

- `Elevator` owns movement and safety rules.
- `Door` owns door state.
- `Button` owns button state.
- `HallPanel` and `CarPanel` own button composition.
- `ElevatorSystemService` owns orchestration.
- `BuildingRepository` owns persistence abstraction.
- `AuditService` owns audit records.
- `MetricsCollector` owns metrics.

### Open/Closed Principle

The design can be extended without rewriting the core model:

- add a dispatcher for multiple elevators,
- add a scheduling strategy for more advanced stop selection,
- replace in-memory repository with persistent storage,
- add new button types or panel layouts.

### Dependency Inversion Principle

`ElevatorSystemService` depends on the `BuildingRepository` interface instead of a concrete repository implementation.

## Current Simplifications

The implementation is intentionally interview-sized. Current simplifications:

- one default elevator is used,
- no real-time scheduler,
- no multi-elevator assignment,
- no passenger identity,
- no door timer,
- no maintenance mode,
- no fire service mode,
- no concurrent request handling.

These are reasonable omissions for a 60-minute OOD solution.

## Implementation Notes

`tick()` allows an idle elevator to start moving if there are pending stops. It only returns immediately for emergency mode, because an idle elevator with pending stops should still be able to respond to a button press.

`updateLoad(loadKg)` models a load sensor reading, not a passenger-by-passenger weight delta. Passing `700` means the elevator currently weighs 700kg, not that another 700kg was added.

When choosing the next stop, if the elevator is moving up and no higher stop exists, it reverses toward the highest lower stop. If it is moving down and no lower stop exists, it reverses toward the lowest higher stop:

```java
if (direction == Direction.UP) {
    Integer next = stops.ceiling(currentFloor);
    return next != null ? next : stops.last();
}

if (direction == Direction.DOWN) {
    Integer next = stops.floor(currentFloor);
    return next != null ? next : stops.first();
}
```

## Recommended Tests

Useful test cases:

- pressing a hall button creates a hall request and adds a stop,
- pressing a car floor button creates a car request and adds a stop,
- elevator closes the door before moving,
- elevator opens the door when arriving at a requested floor,
- multiple floor button presses are aggregated into pending stops,
- emergency clears stops and opens the door,
- elevator does not accept movement while in emergency mode,
- overloaded elevator opens the door and does not move,
- elevator resumes movement after overload is resolved,
- invalid floor requests throw validation errors,
- invalid hall direction such as `IDLE` throws validation errors.

## Interview Explanation

A concise way to explain the design:

> The elevator system is split into physical components and orchestration. `Building` owns floors, hall panels, and elevators. `HallPanel` and `CarPanel` convert button presses into request objects. `Elevator` aggregates those requests into pending stops and owns the movement, door, emergency, and overload safety rules. `ElevatorSystemService` coordinates repository, audit, metrics, and domain operations. The design starts with one default elevator for simplicity, but can be extended with a dispatcher and scheduling strategy for multi-elevator buildings.
