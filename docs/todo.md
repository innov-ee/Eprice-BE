# TODO
- Daily price stats/cache are keyed and fetched using UTC day boundaries (00:00-24:00 UTC), not each country's local calendar day (e.g. Europe/Tallinn), so cached "daily" values don't actually correspond to that country's real day.
- Persist cache over restarts (e.g mount outside of container)
- Requests for tomorrows prices/stats can miss cache before noon, and this means they fall thru to actual requests every time - undesired, improve!
- Consider removing Result wrappers

# Nice to have
- Clean up routing a lot (that class should have minimal to no changes when any route is changed/added/removed)
- Figure out the best way to handle time. (tiemstamp, bespoke class, zones, etc)
- Figure out api contract, e.g sending double values or strings or smth else insstead?
- Implement proper logger
- Consider single endpoint that provides all/most of the data app needs.
- Pass proper coroutine scopes
- Ensure coroutine CancellationExceptions are not swallowed

# Pre live
- Remove GET endpoint for clearing cache
- Make API only accessible to the app.
