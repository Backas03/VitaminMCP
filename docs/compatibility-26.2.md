# Native Minecraft 26.2 investigation

Status: blocked on native bot dependency support. This document and the opt-in
probe do **not** add 26.2 to the supported version matrix.

## Reproduce

Use a disposable Paper 26.2 server bound to loopback, with `online-mode=false`.
Do not change a production server's authentication. Java 25 is required for Paper.

```sh
cd bot/bot-runner-node
npm ci
npm run test:26.2 -- 127.0.0.1 25575
```

The probe checks the actual server-list protocol (776), then resolves a native
runner version before invoking the existing lifecycle checks. It never forces
protocol 775 or falls back to a translated connection. It exits nonzero if the
target is not 26.2, native protocol support is unavailable, or lifecycle checks fail.
It is opt-in and is not included in `npm test`.

## Observed results

Tested on Windows with Java 25, Paper 26.2 build 133, and VitaminMCP 3.2.0.

- The agent loads with Geyser 2.11.3-b1251, Floodgate 2.2.5-b141, and ViaVersion 5.12.1.
- The bundled runner rejects protocol 776 at session startup:
  `This runner cannot speak protocol 776 (26.2)`.
- Updating to published Mineflayer 4.39.0 and minecraft-protocol 1.68.0 still leaves
  26.1 as their latest supported version. The data registry knows protocol 776, but
  data availability alone is insufficient for the runner to support it.
- As a separate workaround experiment, adding ViaBackwards 5.12.1 and explicitly
  choosing protocol 775 allowed a bot to join. Four iron armor slots were verified;
  the bot opened a chest and received its three diamonds; a block break was verified
  with both final AIR state and BlockBreakEvent. The scenarios passed 14 and 5 steps.
- An intentional diamond-helmet assertion against an iron helmet failed correctly.

Those workaround results establish neither native 26.2 support nor Bedrock rendering.
No Bedrock client or custom armor model was tested.

## Remaining work before claiming support

1. Update to bot/protocol dependencies that support native protocol 776.
2. Pass this probe without a protocol override or compatibility translation.
3. Run the project's `CompatibilityLiveTest` gate on 26.2, plus the existing matrix
   to check older versions. The lifecycle probe alone is insufficient.
4. Confirm protocol data survives the slim/SEA packaging step and test the packaged runner.
5. Add the tested Paper build to `versions.yaml` and update the supported-version docs.

The supported matrix is deliberately unchanged until these checks pass.
