# parity/

`EndpointID.swift` is a copy of the iOS client's closed list of API endpoints. It is read by
`EndpointParityTest` in `:core:core-network`, which diffs it against this client's API
interfaces so that an endpoint added on one platform cannot go unnoticed on the other. Nothing
compiles it; only its `case` lines are read.

## Where it comes from

| | |
| --- | --- |
| Upstream | the District iOS client, [`distronode-corporation/district-ios`](https://github.com/distronode-corporation/district-ios) |
| Path upstream | `Packages/DistrictCore/Sources/DistrictNetwork/EndpointID.swift` |
| Upstream commit | [`4be221e`](https://github.com/distronode-corporation/district-ios/commit/4be221e676b84e6c29723c9aad43fca9d23bba74) ("Initial public release", 2026-09-21) |
| SHA-256 of this copy | `4cafe01bfca482388649c8a339d57fc77b8fe2afd2f43a2bdb54081dcd3fd7a6` |

The file is copied byte for byte. Do not edit it here: a local edit makes the parity test
compare this client against an iOS client that does not exist.

## Refreshing it

From a checkout of district-ios at the commit you want:

```sh
cp ../district-ios/Packages/DistrictCore/Sources/DistrictNetwork/EndpointID.swift parity/EndpointID.swift
sha256sum parity/EndpointID.swift
./gradlew :core:core-network:testDebugUnitTest --tests '*EndpointParityTest'
```

Then update the commit and the SHA-256 in the table above, in the same commit as the copy. If the
parity test fails after a refresh, iOS has gained or lost an endpoint: implement it here or
record it in `EndpointParity.kt`, as the failure message says.

## Testing against a live checkout without copying

`DISTRICT_IOS_ENDPOINT_IDS` points the test at any `EndpointID.swift` and takes precedence over
this snapshot. A relative path resolves against the repository root.

```sh
DISTRICT_IOS_ENDPOINT_IDS=../district-ios/Packages/DistrictCore/Sources/DistrictNetwork/EndpointID.swift \
  ./gradlew :core:core-network:testDebugUnitTest --tests '*EndpointParityTest'
```

If the variable names a file that does not exist, the test fails and says so. It does not fall
back to the snapshot.
