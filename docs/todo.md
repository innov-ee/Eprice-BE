# TODO
- Persist cache over restarts (e.g mount outside of container)
- Requests for tomorrows prices/stats can miss cache before noon, and this means they fall thru to actual requests every time - undesired, improve!
- Consider removing Result wrappers

# Nice to have
- Clean up routing a lot (that class should have minimal to no changes when any route is changed/added/removed)
- Figure out the best way to handle time. (tiemstamp, bespoke class, zones, etc)
- Figure out api contract, e.g sending double values or strings or smth else insstead?
- Implement proper logger
- Pass proper coroutine scopes
- Ensure coroutine CancellationExceptions are not swallowed
- Verify how missing prices are handled.

# Nits
- Logging sometimes happens twice per request: in UseCase and Routes

# Pre live
- Remove GET endpoint for clearing cache
- Make API only accessible to the app.
