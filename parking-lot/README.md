# Parking Lot

An object-oriented parking-lot system, built as a data-modeling / OOD exercise
but held to backend-infrastructure standards: concurrency-safe spot allocation,
optimistic-concurrency exit, pluggable assignment and pricing policies, role
separation (driver vs admin), and a metrics + audit observability surface.

## Problem

Model a multi-level garage that admits **different vehicle classes** into
**different spot classes**, meters time through **entry/exit gates**, **charges**
on the way out, and exposes both a **driver** flow (park, pay, exit) and an
**admin** flow (take spots out of service, read occupancy). It must stay correct
when many vehicles arrive at once — no spot handed to two cars, no spot freed
twice, no revenue lost.

## Why it's more than a toy

The interesting part of a parking lot is not the class diagram, it's the
**contention**: the last spot when 200 cars race for 50 bays, and the barrier
that two terminals try to lift on the same ticket. Those are the parts modeled
here with real concurrency primitives and tested under load, not hand-waved.

## Languages

Following the InfraHack layout, the shared problem lives here and each
implementation lives in its own folder:

- [`java/`](java/parking-lot) — reference implementation (JDK 25, TestNG).

See [`java/parking-lot/docs/DESIGN.md`](java/parking-lot/docs/DESIGN.md) for the
full design deep-dive (architecture, invariants, failure modes, scaling, and
interview challenge questions).
