# Where to process API data

## Context and Problem Statement

The app doesnt need all of the data the entso-e api provides. Also the api has gaps in pricing data, so the raw data needs processing before its usable for our purposes.

## Considered Options

1. Process the raw API data on app. Service is just dumb cache/relay. This is safe from cost standpoint, service doesnt need to do much. Most development will focus on FE.
2. Process the raw API data on service. App gets clean data in our own format. This is good because app is not coupled to data source. Requires BE and FE development. Potential versioning issues during developing the best format.

## Decision Outcome

Chosen option: 2.

Because pricing data should change only once per day. Therefore it should be possible to transform the data just once in the service, before caching it. From that point forward its just as expensive as option 1, but with the benefits of decoupling in option 2.