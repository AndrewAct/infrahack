# Demand-Side Ads Relational Table Design

## Problem Statement

Design the core relational data model for a demand-side advertising system. In this domain, external advertisers or partners buy Netflix ad inventory. The system lets an advertiser create campaigns, split those campaigns into ad groups or line items, attach ads and creatives, configure targeting, budgets, dates, pacing, and status, then report impressions and clicks back to the correct advertiser, campaign, ad group, ad, and creative.

The interview is not mainly testing ad-tech vocabulary. It is testing whether you can choose the correct data grain, normalize strong business relationships, explain where each field belongs, and identify the places where production systems intentionally denormalize for reporting or serving performance.

## Key Domain Vocabulary

- Advertiser or Partner: the business account buying ads, such as Apple, Tmall, or Nike.
- Campaign: a marketing initiative under an advertiser, such as "iPhone Launch Q4".
- Ad Group or Line Item: the delivery unit under a campaign. It groups ads that share targeting, budget, bid, pacing, dates, and status.
- Ad: the runnable ad instance under an ad group. It points to a creative and carries click/tracking configuration.
- Creative: the actual asset, such as a video, image, or HTML/rich-media unit.
- Impression: one successful ad exposure.
- Click: one user click on an ad.
- Targeting: rules that decide who is eligible to see an ad.
- Pacing: the strategy for spending budget over time, such as spending evenly or as fast as possible.

## Clarifying Questions To Ask

Start by confirming the grain and scope:

```text
Is this system modeling the advertiser-facing demand side, where partners buy Netflix inventory?
Is one row in the top-level table an advertiser/partner account, or one campaign under that account?
Should targeting live at the campaign level, ad group level, or both?
Do reporting events need to preserve historical versions of campaign/ad group/ad configuration?
Are we designing the normalized management database only, or also the low-latency serving and analytics stores?
```

A strong default assumption:

```text
Advertiser is the account. Campaign is the marketing initiative. Ad group is the delivery unit. Ad is the runnable unit that points to a creative. Reporting events denormalize IDs for fast attribution.
```

## High-Level Architecture

```text
Advertiser / Partner
  -> Campaign
    -> Ad Group / Line Item
      -> Ad
        -> Creative

Ad Group
  -> Targeting Set
    -> Targeting Criteria

Ad Serving / Tracking
  -> Ad Event fact records
    -> Reporting summaries

Configuration Changes
  -> Audit Events
  -> Entity Versions / Snapshots
```

The normalized operational store owns the canonical configuration. A production system would usually add a compiled serving cache for low-latency ad decisioning and an analytical warehouse for high-volume event reporting.

## Core Relational Schema

### advertisers

```sql
advertisers(
  advertiser_id primary key,
  name,
  status,
  created_at,
  updated_at
)
```

One advertiser owns many campaigns.

### campaigns

```sql
campaigns(
  campaign_id primary key,
  advertiser_id references advertisers(advertiser_id),
  name,
  objective,
  status,
  start_at,
  end_at,
  total_budget_amount,
  currency,
  pacing_strategy,
  version,
  created_at,
  updated_at
)
```

A campaign belongs to exactly one advertiser. A campaign contains many ad groups.

### ad_groups

```sql
ad_groups(
  ad_group_id primary key,
  campaign_id references campaigns(campaign_id),
  name,
  status,
  start_at,
  end_at,
  daily_budget_amount,
  lifetime_budget_amount,
  bid_amount,
  bid_strategy,
  pacing_strategy,
  version,
  created_at,
  updated_at
)
```

An ad group belongs to exactly one campaign. It contains many ads. Most delivery controls live here because this is the line-item level.

### creatives

```sql
creatives(
  creative_id primary key,
  advertiser_id references advertisers(advertiser_id),
  creative_type,
  asset_url,
  approval_status,
  metadata_json,
  created_at,
  updated_at
)
```

A creative belongs to an advertiser. A creative can be reused by multiple ads, as long as ownership and policy rules allow it.

### ads

```sql
ads(
  ad_id primary key,
  ad_group_id references ad_groups(ad_group_id),
  creative_id references creatives(creative_id),
  status,
  click_url,
  rotation_weight,
  version,
  created_at,
  updated_at
)
```

An ad belongs to one ad group and points to one creative. This separates the asset from the runnable delivery instance.

## Cardinalities

```text
Advertiser 1 -> many Campaigns
Campaign 1 -> many Ad Groups
Ad Group 1 -> many Ads
Creative 1 -> many Ads
Ad 1 -> many Ad Events
Ad Group 1 -> one or many Targeting Sets, depending on versioning strategy
Targeting Set 1 -> many Targeting Criteria
```

For a 60-minute OOD implementation, it is fine to simplify to one current targeting set per ad group.

## Field Placement

### Advertiser-level fields

Advertiser-level fields describe the business account, not one marketing initiative:

```text
name
account status
billing/account metadata
partner-level defaults
created_at / updated_at
```

Do not put campaign-specific budgets or dates here.

### Campaign-level fields

Campaign fields describe the marketing initiative:

```text
objective
total budget
campaign start/end dates
campaign status
high-level pacing
currency
```

A campaign answers: what is the business goal and top-level budget envelope?

### Ad-group-level fields

Ad group fields describe delivery strategy:

```text
targeting
bid / bid strategy
daily or lifetime budget
ad group start/end dates
pacing override
status
frequency cap, if included
```

An ad group answers: who do we target, how much do we bid, and how does this line item spend?

### Ad-level fields

Ad fields describe the runnable ad instance:

```text
creative_id
click_url
tracking parameters
rotation weight
ad status
version
```

Do not store impressions directly on the ad. Impressions are events, not static ad configuration.

### Creative-level fields

Creative fields describe the asset itself:

```text
creative type
asset URL
approval status
duration / dimensions / metadata
```

A creative can exist before or outside one specific ad group.

## Targeting Model

Targeting is best modeled as a rule set, not fixed columns, because dimensions change often and many are multi-value.

### Simple 60-minute version

Attach targeting directly to ad groups:

```sql
targeting_sets(
  targeting_set_id primary key,
  ad_group_id references ad_groups(ad_group_id),
  version,
  created_at,
  updated_at
)

targeting_criteria(
  criterion_id primary key,
  targeting_set_id references targeting_sets(targeting_set_id),
  dimension_type,
  operator,
  value_type,
  value_id
)
```

Example:

```text
AdGroup: US iOS New Users
  INCLUDE GEO COUNTRY US
  INCLUDE DEVICE OS IOS
  EXCLUDE AUDIENCE SEGMENT existing_customers
```

### Generalized version

If targeting can belong to campaign, ad group, or advertiser defaults, use a polymorphic owner:

```sql
targeting_sets(
  targeting_set_id primary key,
  owner_type,
  owner_id,
  version,
  created_at,
  updated_at
)
```

Tradeoff: `owner_type + owner_id` is flexible, but it weakens database-level foreign-key enforcement because `owner_id` may point to different tables. For the interview, prefer `ad_group_id` unless the interviewer asks for inherited targeting.

## Targeting Columns vs Separate Tables

### Fixed columns

```sql
ad_groups(country, device_type, os, audience_segment)
```

Pros:

```text
Simple to write
Fast to query
Easy for a small fixed scope
```

Cons:

```text
Hard to support multi-value rules
Hard to support include and exclude
Requires schema migrations for new dimensions
Becomes messy with country_1, country_2, excluded_country, etc.
```

### Separate targeting tables

Pros:

```text
Normalized
Extensible
Supports multiple dimensions
Supports include/exclude
Can version targeting independently
```

Cons:

```text
More joins
More validation logic
Not ideal for real-time serving without compilation or caching
```

Production compromise:

```text
Use normalized targeting tables for management writes.
Compile them into a denormalized serving snapshot or cache for low-latency ad selection.
```

## Reporting Model

Reporting should make it easy to attribute an impression or click back to the full hierarchy.

```sql
ad_events(
  event_id primary key,
  event_type,
  event_time,
  advertiser_id,
  campaign_id,
  ad_group_id,
  ad_id,
  creative_id,
  campaign_version,
  ad_group_version,
  ad_version,
  cost_amount,
  placement_id,
  user_id_hash
)
```

This table intentionally denormalizes join keys. Even though `ad_id` can theoretically join back to ad group, campaign, and advertiser, the event should preserve attribution as it was at event time.

Reasons to denormalize reporting events:

```text
Historical correctness if campaigns/ad groups/ads change later
Faster aggregation by advertiser, campaign, ad group, or ad
Less dependency on current operational tables during analytics queries
Simpler event ingestion pipeline
```

A production analytical system would likely stream these events into Kafka and store them in an OLAP table or warehouse, such as Iceberg, Hive, BigQuery, Snowflake, or similar.

## Versioning and Audit

There are two related but different needs:

```text
Versioning: what version of the configuration was active?
Audit: who changed what and when?
```

Operational tables can have a `version` column for optimistic locking and event attribution.

```sql
audit_events(
  audit_event_id primary key,
  entity_type,
  entity_id,
  actor,
  action,
  before_json,
  after_json,
  created_at
)
```

A more advanced system can store snapshots:

```sql
entity_versions(
  version_id primary key,
  entity_type,
  entity_id,
  version_number,
  snapshot_json,
  effective_from,
  effective_to,
  created_at
)
```

For the OOD implementation, a simple `AuditEvent(entityType, entityId, action, actor, createdAt)` is enough to show the design direction.

## Serving Eligibility Invariants

An ad should be eligible to serve only if all required parents are eligible:

```text
advertiser.status == ACTIVE
campaign.status == ACTIVE
ad_group.status == ACTIVE
ad.status == ACTIVE
now is within campaign start/end
now is within ad group start/end
creative is approved/active
targeting matches the user/context
budget remains available
```

This invariant is useful to mention even if the 60-minute implementation does not build a real serving engine.

## SQL vs NoSQL Tradeoff

The prompt asks for relational tables, so the core answer should be relational. But real advertising systems often use a hybrid architecture.

Use relational storage for:

```text
advertiser/campaign/ad group/ad hierarchy
foreign-key relationships
budgets
status
flight dates
audit metadata
transactional management workflows
```

Use document or NoSQL storage for:

```text
partner-specific specs
custom integration configuration
creative rendering rules
measurement vendor configuration
flexible policy blobs
experimental feature flags
```

Use analytical storage for:

```text
impression events
click events
spend metrics
aggregated reporting
large-scale historical queries
```

A strong interview answer:

```text
For this OOD prompt, I will model the normalized relational source of truth. In production, I would use relational storage for the core entity graph, document or NoSQL storage for flexible partner-specific specs and configs, a cache or compiled snapshot for serving, and an analytical store for high-volume reporting events.
```

## 60-Minute Implementation Strategy

Keep the code small and defensible:

```text
1. Define enums: Status, EventType, CreativeType, PacingStrategy, TargetingDimension, TargetingOperator.
2. Define core models: Advertiser, Campaign, AdGroup, Ad, Creative, Budget.
3. Define targeting models: TargetingSet, TargetingCriterion.
4. Define reporting models: AdEvent, ReportingSummary.
5. Define audit model: AuditEvent.
6. Define repository interfaces and in-memory implementations.
7. Define service methods that validate parent existence and ownership.
8. Add tests for hierarchy creation, targeting update, event attribution, and invalid creative ownership.
```

Avoid spending time on:

```text
ORM annotations
real SQL execution
full ad-serving algorithm
full lifecycle transition graph
complex bid strategies
large partner config system
```

Those can be discussed as extensions.

## Common Interview Pitfalls

- Confusing advertiser/partner with campaign.
- Treating ad group as the target audience itself instead of the delivery unit.
- Putting every field on campaign and leaving ad group meaningless.
- Storing impressions as a field on `ads` instead of event/fact records.
- Over-normalizing reporting events and losing historical attribution.
- Hard-coding targeting as a few columns without explaining the tradeoff.
- Forgetting that creatives can be reused across ads.
- Ignoring versioning when configuration changes over time.
- Not explaining why production serving would use snapshots or cache.

## Good Interview Sound Bites

```text
The grain of campaign is a marketing initiative, not the advertiser account.
```

```text
Ad group is the delivery unit: targeting, budget, bid, pacing, dates, and status live there.
```

```text
Ad is configuration; impression and click are events.
```

```text
Operational data should be normalized, while reporting events are intentionally denormalized for attribution and query performance.
```

```text
Targeting is modeled as criteria because dimensions are extensible and often multi-value.
```

```text
Versioning preserves what configuration was active when an impression or click occurred; audit preserves who changed it and when.
```

## Minimal Object Model Mapping

The Java OOD skeleton can map the schema like this:

```text
Advertiser -> advertisers
Campaign -> campaigns
AdGroup -> ad_groups
Ad -> ads
Creative -> creatives
TargetingSet -> targeting_sets
TargetingCriterion -> targeting_criteria
AdEvent -> ad_events
AuditEvent -> audit_events
```

In the object model, it is acceptable for `AdGroup` to contain a `TargetingSet`. In the relational model, that still maps cleanly to separate targeting tables using `ad_group_id` and `targeting_set_id` foreign keys.

## Production Extensions

If there is more time or the interviewer pushes deeper, discuss:

```text
Budget spend ledger and reconciliation
Frequency caps
Campaign/ad group targeting inheritance
Creative approval workflow
Status transition validation
Optimistic locking on version
Compiled serving snapshots
Event idempotency and deduplication
Attribution windows
Data retention and privacy controls
Partner-specific config in JSON or NoSQL
OLAP rollups by hour/day/campaign/ad group/ad
```
