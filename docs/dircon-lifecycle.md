# DirCon lifecycle and notification ownership

Concurrent transport startup could construct two DirCon servers before `BleServer` stored either
reference. One could bind TCP 8081 while the other became the notification destination and failed
with EADDRINUSE. Discovery/reads on the listening instance could still work through the shared GATT
bridge, while notifications went to an instance with no connected clients. Saved November 3–4 logs
show the warning predates the temperature experiment; the live field probe reproduced successful
reads and acknowledged subscriptions with no notifications.

`BleServer` now retains one lazy DirCon owner and serializes transport transitions on its existing
monitor. The server publishes a lazy startup job before executing it, binds on IO, and coordinates
socket publication/close with a generation token. A stopped generation cannot advertise, register a
client, or close the next generation's socket. Disconnect callbacks run outside the lifecycle lock
because they can acquire the BLE monitor. A failed bind retires the job so startup can be retried.
Advertising starts only after binding succeeds; the UI shows actual socket status rather than the
enabled preference. A stale mDNS failure callback cannot clear a later registration's listener.

Unit regressions use loopback sockets with mocked mDNS only: concurrent starts, stable ownership
across stop/start, occupied-port failure and retry, client-local subscription/unsubscription, rapid
generation changes, and subscribed-client shutdown. They send no fitness control commands.

Device validation should retain calibration/settings, verify exact installed bytes, then use a
separate TCP client for discovery, reads, and temporary telemetry subscriptions. Check actual
unsolicited Indoor Bike Data and Cycling Power frames, not just a listening port or a successful
subscription response. Never exercise resistance/control writes to test network broadcasting.
