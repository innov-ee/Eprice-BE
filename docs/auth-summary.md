## Summary of how auth is implemented

### Requirements
- Protect apis from unauthorized access
- keep the solution cheap, can be improved upon later
- provide admin access to admin panel (monitor.html at moment)
- no dependency on google/apple - i.e. no device attestation.

### Concept
Relying on auth headers and https:

- Server expects a valid auth key in the request header.
- The auth key is not shipped with app to simplify rotating keys.
  - All clients usa same auth key
  - The key is rotated every 14 days
- Theres an endpoint to fetch auth keys from
  - Meaning the client is still shipped with a "bootstrap key" that can access (only) the api for getting the auth key
    - server supports multiple bootstrap keys to allow simpler bootstrap key rotation.
  - it is rate limited by nginx to deter guessing the bootstrap key.
    - if an attacker decompiles the app, they can get it anyway.
- Server doesn't store keys, it stores a master key from which all keys are derived from and can be tested against.
- https hides the keys, protecting against basic network snooping.

### Implementation

#### VPS
Nginx gets different rules per request path

#### Ktor server
- specify defaults in env: BOOTSTRAP_KEYS, ADMIN_USERNAME, ADMIN_PASSWORD
- install authentication plugin
  - Register `apiKey` based auth (i.e. the header name (`X-API-Key`, `X-Bootstrap-Key`) and validation operation) for auth and bootstrap keys.
    - Need to implement `AuthenticationProvider`, which encapsulates reading the header and then either authenticating the call for further processing, or responding with failure.
  - Register `basic` auth (i.e. the login dialog) for admin access.
  - Equality is done using `messageDigest.isEqual` to avoid timing based attacks. (in standard equals: the longer it takes, the longer substring match you have)
- wrap Routes in `authenticate` blocks naming which auth strategy to support among the above.
- Add KeyRotationService to encapsulate deriving and testing keys from master keys.
  - A key is generated from 2 seeds: A) current time bucket (ie the 14d period) and B) the master key
  - whenever a request comes in the service regenerates the key and compares it (acceptable)


### Problems:
- Change admin panel to allow spcifiying bootstrap key header in the request. and prefill it with current bootstrap key. (prehaps change the catalog to allow listing headers, that would surface auth header also, dunno)
> TODO: i dont like the coupling (nginx has to know about service internals) it brings, re think routes to simplify, or unify limiting, or remove it.
