# Provisional aluminum disc / steel hub replay

Recommendation: Cd=2000 J/K, Ch=6000 J/K, disc-hub G=8 W/K, disc-air Hd=4 W/K, hub-air Hh=4 W/K. Deposit raw native-bike heat input at the disc and expose disc temperature as the experimental brake-surface estimate. Persist both states. These are effective trial constants, not measured masses or material specifications.

Equations: Cd*dTd/dt=P-Hd*(Td-Ta)-G*(Td-Th); Ch*dTh/dt=G*(Td-Th)-Hh*(Th-Ta). Total effective heat capacity remains 8000 J/K and ambient conductance sum remains 8 W/K. The split does NOT preserve the old steady disc temperature: effective steady disc conductance is Hd+G*Hh/(G+Hh)=6.667 W/K. At 200W and 69F ambient, steady disc=123F and hub=105F. Decay mode time constants are approximately 142 and 1058 seconds, enabling a fast disc response plus slow retained hub heat.

## Retrospective blocked comparison

Primary replay reconstructs interval-constant power from the rounded original C8000/H8 app display, assuming 69F ambient, then runs all candidate models continuously from both states at69F. It is a relative response comparison, not independent power validation. Display inference depends on the original model and can be circular; treat it as sensitivity evidence, not independent truth. Samples1-5 have invalid rim proxies and are excluded from scoring but their display evolution supplies input. Train grid selection uses original6-40 only. Later blocks have no temperature-state resets and do not enter the grid objective. The conservative recommended capacity is not the lowest training error. Because these are retrospective checks on one already examined ride, call them blocked replay checks, not prospective validation.

RMSE in F, blocks: train original6-40 / original41-55 / reduced-power56-60 / additional1-15:
- Old C8000/H8: 15.43 / 10.81 / 6.50 / 18.13.
- C6400/H8: 12.35 / 8.71 / 5.57 / 16.72.
- Recommended2000/6000, G8, Hd4/Hh4: 3.35 / 1.51 / 1.45 / 3.36.
- Lowest training grid1000/7000, G8, Hd4/Hh4: 2.18 / 2.42 / 2.92 / 2.45.

A separate UDP-proxy replay gives recommended RMSE2.49 /0.94 /0.81 /3.91F. UDP Main W is not established as the estimator's raw native bike watts. Interval means substitute for display input only with at least0.5 logged Main rows per second of the interval; first interval, original59-to60 and original-to-additional gap fall back to reconstructed display input. The last gap is bridged by mean input, not known within-gap chronology. This is a robustness check, not independent validation.

## Sensitivity and identification limits

With G8 and Hd4/Hh4, Cd1600 through3200 (total8000 held) gives train RMSE2.61-5.85F and additional2.73-5.22F. Cd2000 is a restrained trial choice rather than forcing the fast, lower-capacity training optimum. At Cd2000 and fixed loss split, G4 gives8.16F additional RMSE; G6 3.67F; G8 3.36F; G10 4.99F; G12 6.56F; G16 8.90F. Coupling near6-10 is a useful trial neighborhood, not an identified confidence interval. Changing loss split compensates coupling: Cd2000, G4, Hd5/Hh3 also gives2.36F train and3.90F additional. Thus no unique coupling or masses are inferred.

Assumed ambient65-75F with the input reconstruction adjusted consistently gives recommended additional RMSE2.94-4.21F. Multiplying inferred input by0.9 or1.1 gives additional RMSE9.16 or5.77F, versus3.36 nominal: input scale is materially confounded with conductance. A20% capacity-only reduction cannot correct the persistent steady-temperature discrepancy.

The IR rim scene-maximum proxy is not bulk temperature; emissivity, reflected temperature and ambient remain unverified. Center spot is not an identified steel-hub temperature and was not used as the hub target. The painted bolted interface is a plausible thermal resistance, not a measured G. Aluminum assumption supports the two-region interpretation but does not determine effective masses. There is no true zero-input cooldown; late readings include variable continuing power. The user report98F measured/90F estimated lacks timing and power/fan history and was not included in fitting.

## Reproduce

Run `node tools/flywheel_thermal_replay.cjs <local-recording-directory>` with the original verified readings and UDP logs. The raw recording files are intentionally not included in the repository. The script writes `two_node_analysis_results.json` beside those inputs, containing full values and the top 20 grid candidates. Midpoint integration at2s agrees with0.2s within0.000205F for the recommendation. The application uses an exact analytic step; replay uses independent midpoint integration.

No further ride testing is requested or scheduled. These checks do not establish unique physical parameters, passive cooling accuracy or guaranteed degree-level live accuracy.

## Implementation checks

- 599 JVM unit tests passed, including two-node numerical, energy, lifecycle and calibration regressions.
- Android instrumentation test sources compile; they were not run on a device or emulator.
- The release variant builds using the local debug-signing fallback; no production signing or installation.
- Release artifact contracts (13), emulator-fixture contracts (5), and release-version cases (6) passed.
- Reproducible local replay completed and `git diff --check` passed.
- Full debug lint is not clean: the unchanged `CalibrationCaptureService.kt:36` notification call
  reports `NotificationPermission`. Source, manifest and build configuration for that issue were
  checked against the base commit and are unchanged. Lint reports 67 warnings and 8 hints as well.
