# TODO
- Always use SDAC day-ahead prices consistently for prices and statistics. ENTSO-E returns both SDAC (Sequence 1) and EXAA (Sequence 2) for Germany–Luxembourg; Austria also publishes both. In `EntsoeService`, request `contract_MarketAgreement.type=A01` and `classificationSequence_AttributeInstanceComponent.position=1` for regions with multiple sequences. Add the sequence field to `TimeSeries` and validate/filter it before merging intervals; never mix, average, or silently fall back to EXAA. Test conflicting Sequence 1/2 prices and missing SDAC data. Live DE-LU responses currently fail with "Conflicting price series for the same UTC interval" at both 7-day and 32-day ranges. See [ENTSO-E explanatory notes](https://eepublicdownloads.blob.core.windows.net/tp-reporting-exports/Explanatory%20Notes.xlsx).
- Requests for tomorrows prices can miss cache before noon, and this means they fall thru to actual requests every time - undesired, improve!
- For each region also list currency

# Nice to have
- Consider removing Result wrappers
- Clean up routing a lot (that class should have minimal to no changes when any route is changed/added/removed)
- Figure out the best way to handle time. (tiemstamp, bespoke class, zones, etc)
- Figure out api contract, e.g sending double values or strings or smth else insstead?
- Implement proper logger
- Pass proper coroutine scopes
- Ensure coroutine CancellationExceptions are not swallowed
- Verify how missing prices are handled.

# Nits
- Logging sometimes happens twice per request: in UseCase and Routes
- Move admin panel also behind stricter rate limiting
