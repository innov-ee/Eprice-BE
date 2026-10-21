# TODO
- Requests for tomorrows prices can miss cache before noon, and this means they fall thru to actual requests every time - undesired, improve!
- For each region also list currency

# Nice to have
- Consider limiting ENTSO-E fallback to days due for refresh. Currently a full-window Elering refresh can trigger one extra request per consecutive group of missing days, even if those days are already cached or in cooldown. And a failed unnecessary request can fail the summary.
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
