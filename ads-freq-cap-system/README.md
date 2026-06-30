# Ads Frequency Capping System

This repo documents a system design for an advertising frequency-capping service.

Problem statement:

> Given an ad request and one or more candidate ads, decide whether a user can see the candidate ad, ad group, campaign, or household-scoped campaign without exceeding configured frequency caps. The core version uses rolling windows: at most `N` impressions during the last `W` units of time.

## What This System Solves

Frequency capping protects user experience and campaign rules by preventing overexposure, for example:

* A user may see the same ad at most 3 times in the last 24 hours.
* A user may see a campaign at most 10 times in the last 7 days.
* A household may see a campaign at most 20 times in the last 7 days.

The important design tension is that the ad-serving path needs very low latency, while impression counting needs durability, replayability, deduplication, and correctness under retries and distributed failures.

## Core Architecture

The design separates two paths:

* Read path: online cap check before serving an ad.
* Write path: durable impression event ingestion after an ad is actually rendered or viewable.

High-level flow:

```text
Ad request
  -> Ad Serving Service
  -> Candidate Generation / Ranking
  -> Frequency Cap Service
  -> Online Counter Store / Rolling Window Store
  -> eligible ads
  -> ad rendered
  -> Impression Tracking Endpoint
  -> Kafka
  -> Flink
  -> counters + raw event lake
```

## Main Design Choices

For high-volume best-effort caps:

* Use bucketed rolling counters.
* Example: a 24-hour rolling window can be represented by 5-minute or 1-hour buckets.
* Reads sum recent buckets; writes increment the current event-time bucket.
* This is scalable and low latency, but approximate around bucket boundaries.

For exact strict caps:

* Use timestamp history or a reservation path.
* Example: Redis sorted set / strongly consistent store with atomic trim, count, and add.
* This prevents overshoot but increases latency, write amplification, and coordination cost.

For durable processing:

* Kafka stores short-term transport logs.
* Flink consumes impression events, deduplicates them, assigns event-time windows, updates counters, and writes raw events to S3/object storage.
* S3/object storage is the long-term source of truth for audit, backfill, billing reconciliation, and offline analytics.

## Key Documents

* [DESIGN.md](./DESIGN.md): detailed 60-minute system design walkthrough.

## Interview One-liner

The core design is a low-latency cap-check service backed by pre-aggregated rolling-window counters, plus a durable Kafka/Flink impression pipeline that updates counters asynchronously and writes raw events to object storage for replay and audit. Normal campaigns accept bounded overshoot; strict campaigns use atomic timestamp history or reservation-based enforcement.
