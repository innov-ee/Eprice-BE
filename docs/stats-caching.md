# Daily statistics caching

Daily statistics use one rule: if any missing local day is due for a check, fetch the entire requested window once. The default summary window is 32 days (30 historical days, today, and tomorrow). There is no single-day special case or subdivision into chunks. If all requested days are cached or missing days are still cooling down, serve the cache without fetching. Responses are clipped to each local day before coverage and statistics are calculated, including 23/25-hour DST days.

Every missing day in the fetched window can recover, even if its availability cooldown has not expired. Complete days are saved and their availability metadata is removed. Still-incomplete days get updated availability metadata only when their check was due; incidental rechecks do not extend active cooldowns or increase their backoff counters. Previously complete cached statistics are preserved, including when a refresh fails or returns incomplete data for those dates.

When Elering returns some data but omits entire local days, those empty days still use the ENTSO-E fallback before availability cooldowns are recorded. Consecutive empty days share a fallback request; days with Elering data are preserved.

Empty and incomplete days have separate, persisted availability metadata (`reason`, `checkedAt`, `nextCheckAt`, `consecutiveMisses`). They remain absent from statistics. Complete days remove this metadata and stay cached indefinitely. Existing daily-stats v2 files load without migration. Clearing the stats cache clears availability metadata too.

The default refresh policy is:

- Tomorrow (or later): after an unsuccessful fetch, wait until at least noon on the preceding day in the region's timezone, or 15 minutes after the check, whichever is later.
- Today and yesterday: retry after 15 minutes.
- Older gaps: start at six hours, double after each unsuccessful check, and cap at seven days. The counter resets when a day becomes older than yesterday, so frequent recent checks do not increase the initial historical delay.

These are refresh choices, not upstream publication guarantees. `StatsRefreshPolicy` accepts alternate times and delays. Refreshes happen on demand when a request arrives after `nextCheckAt`; no background polling is introduced.

Provider failures, timeouts, and malformed responses remain failed results and do not create missing-data metadata. A failed stats fill starts a 30-second error cooldown for that region. Requests needing another upstream fetch return the same error during the cooldown, including requests for different date ranges. Cached days and dates already under an availability cooldown can still be served without fetching. Reads do not extend the error cooldown; the next request at or after expiry can retry. Cancellation does not start an error cooldown. Error cooldowns live only in the repository instance, are not persisted, and accept an alternate duration through `PriceStatsRepositoryImpl`.

Within one application instance, statistics requests share one active fill per region. Requests covered by that fill share its result; overlapping requests wait and then recheck cached days and cooldowns before deciding whether their full window needs a refresh. Different regions run independently. Caller cancellation stops only that caller's wait. The repository's worker scope is cancelled when its Koin singleton closes. Coordination does not extend across application replicas.

This policy applies to statistics. Price requests return and cache available Elering data without attempting to fill empty days through ENTSO-E. If Elering returns no data or fails, price requests still fall back to ENTSO-E for the requested range. Price endpoint caching and HTTP transport retries are configured separately.
