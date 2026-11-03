# External sensor connection behavior

The heart-rate and power pickers show saved devices first in an inset, independently scrollable list. Connection state uses both text and color: green Connected, yellow Connecting, red Not Connected. Scroll hints appear when the list exceeds its window. Disconnect (or Cancel while connecting) before selecting another device of that type. Forget removes only that saved device.

The two sensor types have independent owners: one heart-rate monitor and one power meter may be connected together, but advertisements and rapid repeated selections cannot replace an in-progress or connected device of the same type. Stale callbacks from a released GATT are ignored. A saved disconnected target can reconnect in the background; an empty saved list cannot cause background discovery. Manual disconnect suppresses automatic reconnection for the session.

Discovery demand comes from the respective visible pairing picker, not the dashboard. Closing it, opening heart-rate Zones, or backgrounding the activity removes picker demand. Saved-device reconnect demand is separate. Scanning stops while a GATT connection is pending or active; scans are bounded and backed off on failures.

Calibration Record and Start capture remain tappable without a ready meter so the app can explain the prerequisite with a toast: “Connect a power meter first”. The request is rejected without starting recording. Stopping an existing capture remains possible after meter loss.

Heart-rate zone labels use plain text (for example, “1 to 2”) because the bundled Lato fonts do not contain the former arrow glyph.

## Verification scope

Pure discovery-policy and recording-guard tests cover the demand truth table and meter prerequisite. Manager tests cover repeated selections/advertisements, scan suppression and stale GATT behavior. Compose tests cover saved status, scrollability, competing Connect controls, zone labels and recording control taps. Physical Bluetooth reconnect and ride validation still require the actual sensors; no emulator result substitutes for hardware verification.

This preview includes open PRs #89, #90 and #91 at heads 2fbfd844865ac4c738ac87d597d9b1e28d68b971, 43ad8e1c0204b6bb03eeee76c816d30edf40189b and 76ecf808d4ae8048cbae5f4efa75eb06892738e5 respectively. The first two are ancestors of #91. No production merge or release is implied.
