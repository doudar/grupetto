# FTMS resistance audit

## Finding and correction

The transmit path applied a per-second robust average and then exponential
smoothing (`0.7 * new + 0.3 * previous`) to resistance. The packet encoder then
used `toInt()`, which truncates. A change from 99 to 100 therefore produced 99.7
and transmitted 99. Repeated updates could continue transmitting 99 while the
smoothed value approached 100. Other increasing levels had the same downward
bias; decreases were delayed too. The overlay instead formats the sensor value
to the nearest whole number and does not apply this transmit smoothing.

The transmitter now takes the latest finite resistance from each update buffer.
The encoder rounds to the nearest integer, with half levels rounding up, and
clamps finite readings to 0–100 before byte conversion. Non-finite input encodes
as zero, consistent with the previous no-data fallback. All integer readings
from 0 through 100 are preserved exactly. The supported range still advertises
1–100 in increments of 1; zero remains the no-data/startup value. Its maximum and
the encoder's upper bound now share one constant.

Both BLE and DirCon use the same encoded characteristic value. There is no
additional resistance scaling in either transport.

## Upstream behavior retained

- Original Bike receives integer `BikeData.currentResistance`; Bike+ receives
  integer `BikeData.targetResistance`. Bike+ therefore reports the target, which
  may differ from the physical resistance during an adjustment. Changing that
  source requires checking real Bike+ data.
- Both sensor interfaces take the minimum of three readings to reject spikes.
  This can delay increases until three high readings arrive. This fix matches
  the latest **sensor-interface** value at the approximately one-second transmit
  interval; it does not bypass that upstream filter.

## Wire-format compatibility observation

The existing implementation encodes resistance as a whole-level, little-endian
16-bit value, with six bytes for the supported range. That layout is retained
by this fix: 100 encodes as `64 00`, and the range is `01 00 64 00 01 00`.

There is a separate interoperability concern: the Bluetooth SIG
[GATT Specification Supplement](https://btprodspecificationrefs.blob.core.windows.net/gatt-specification-supplement/GATT_Specification_Supplement.pdf)
(2026-09-09, sections 3.139 and 3.231) lists one-byte resistance fields. Switching
widths would also move the power field and change range decoding. This audit
does not certify the retained format as compliant; any format migration needs
captures from the receiving applications to avoid breaking their decoding.

## Validation

Regression tests cover every integer 0–100, the float immediately below every
integer 1–100, rounding boundaries, invalid/out-of-range input, range/maximum
consistency, complete packet bytes, and the running server's
99 → 100 → 99 → 1 → 0 → 100 update sequence.

No physical bike or receiving-client capture was available for this audit.
