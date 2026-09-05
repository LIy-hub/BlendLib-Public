# Third-party Experimental SPI examples

Each child is a separately built pure-Java package depending only on the published
com.liy.blendlib:blendlib-api coordinate. The packages use explicit Experimental SPI annotations
and never import implementation/internal packages, Minecraft classes, raw GL, reflection, or
GeckoLib.

The examples show offer identity, exact current protocol range, bounded priority, optional safe
fallback metadata, non-hot lifecycle callbacks, lease release ownership, and close behavior. They
do not install themselves, start discovery, or claim that a platform has accepted them. A real
host owns registration, frozen plan selection, leases, lifecycle scheduling, reload, and shutdown.

Static compilation and all runtime lifecycle evidence are **WAITING**: the X8 task does not run
Gradle, a JVM, client, server, provider discovery, or a conversion command.
