# CoreBoost for Minecraft 1.8.9

Client-side Forge 11.15.1.2318 mod for Minecraft 1.8.9, Java 8, SpongePowered Mixin 0.7.11.

## Build

Install JDK 8 and Gradle 4.10.3, then run gradle clean build. The mod JAR is written to build/libs/. GitHub Actions builds it on push, pull request, and manual workflow dispatch and uploads the JAR as a workflow artifact.

## Controls

Use /coreboost or /coreboost status for worker and optimization status. Toggle settings with /coreboost culling off, /coreboost particles on, /coreboost hud toggle, and /coreboost fastmath toggle. Configuration is stored in config/coreboost.cfg.

## Features and safe threading

- Entity/tile visibility snapshots include other players, dropped items, armor stands, chests, and beds. The client thread reads world state and copies coordinates and opaque block collision boxes. Worker tasks perform only field-of-view and ray/AABB math on immutable primitive snapshots. Results expire after two ticks and fail open when stale.
- A particle limiter caps EffectRenderer additions per client tick.
- A bounded FontRenderer width cache avoids repeated CPU-side string measurement, clears on font-resource reload, and never caches OpenGL work.
- A compact trig table is prepared on a worker and used for the culling camera vector. Vanilla 1.8.9 already uses trig lookup tables in MathHelper, so CoreBoost does not overwrite those methods.
- A daemon worker pool is sized to Runtime.getRuntime().availableProcessors(). It handles independent visibility jobs and parsing of copied chat strings. Worker threads never access world objects, renderer state, or OpenGL.
- Vanilla packet decompression/decoding remains in Forge/Netty, and vanilla chunk mesh generation remains on its existing renderer path. CoreBoost does not reorder packets or mutate chunk renderer state off-thread.

This client-only mod sends no custom packets and does not change server gameplay. Turn culling off if a custom renderer or resource pack has unusual visibility behavior.

