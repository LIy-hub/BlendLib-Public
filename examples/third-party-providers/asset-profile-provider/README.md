# AssetProfileProvider example

This package offers one strict rigid-v1 asset-profile capability at current protocol 1.1.0 and
priority 40. Its lifecycle record is diagnostic-only and the provider never parses a GLB in an
offer or lifecycle callback. A session owner, not this package, creates and releases ProviderLease
instances after snapshot consumers finish.
