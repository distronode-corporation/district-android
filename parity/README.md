# parity/

`EndpointID.swift` is a copy of the Apple clients' closed list of API endpoints. It is read by
`EndpointParityTest` in `:core:core-network`, which diffs it against this client's API
interfaces so that an endpoint added on one platform cannot go unnoticed on the other. Nothing
compiles it; only its `case` lines are read.

## Where it comes from

The list lives in the shared Swift core that the iOS and macOS apps both build, not in the iOS
app's repository (whose `Packages/DistrictCore` it left when the core moved out).

| | |
| --- | --- |
| Upstream | the shared Swift core, [`distronode-corporation/district-core-swift`](https://github.com/distronode-corporation/district-core-swift) |
| Path upstream | `Sources/DistrictNetwork/EndpointID.swift` |
| Upstream tag | [`8.0.0`](https://github.com/distronode-corporation/district-core-swift/blob/8.0.0/Sources/DistrictNetwork/EndpointID.swift) (commit [`dabfa0f`](https://github.com/distronode-corporation/district-core-swift/commit/dabfa0fffb76fe190f86be87cb69242e58e5da09), "8.0.0: decline, whisper, notify-read token and message sender") |
| SHA-256 of this copy | `ede0a464f9239b1eb4aabe1ae664ff2badaa7f213c489ec9f740f0486b8a3b1d` |

The file is copied byte for byte. Do not edit it here: a local edit makes the parity test
compare this client against an iOS client that does not exist.

## Refreshing it

From a checkout of district-core-swift at the tag you want:

```sh
cp ../district-core-swift/Sources/DistrictNetwork/EndpointID.swift parity/EndpointID.swift
shasum -a 256 parity/EndpointID.swift
./gradlew :core:core-network:testDebugUnitTest --tests '*EndpointParityTest'
```

Then update the tag, the commit and the SHA-256 in the table above, in the same commit as the
copy. If the parity test fails after a refresh, the Apple clients have gained or lost an
endpoint: implement it here or record it in `EndpointParity.kt`, as the failure message says.

## Testing against a live checkout without copying

`DISTRICT_IOS_ENDPOINT_IDS` points the test at any `EndpointID.swift` and takes precedence over
this snapshot. A relative path resolves against the repository root.

```sh
DISTRICT_IOS_ENDPOINT_IDS=../district-core-swift/Sources/DistrictNetwork/EndpointID.swift \
  ./gradlew :core:core-network:testDebugUnitTest --tests '*EndpointParityTest'
```

If the variable names a file that does not exist, the test fails and says so. It does not fall
back to the snapshot.
