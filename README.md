# IronLog V2 — Android Client

Smoke-test thin client for the IronLog V2 FastAPI server.

See `docs/superpowers/specs/2026-06-24-android-thinclient-design.md` for design,
`CLAUDE.md` for the working agreement, `docs/superpowers/plans/2026-06-24-android-thinclient.md`
for the build plan.

## Build

    ./gradlew :app:assembleDebug

## Install

    adb install -r app/build/outputs/apk/debug/app-debug.apk

Installs alongside the v1 app (`com.jauschua.ironlog`); this app's id is
`com.jauschua.ironlogv2`, label "IronLog V2".

## Server

The app talks to `http://myflix.media:8000`. Start the server with:

    uvicorn ironlog.api.app:app --host 0.0.0.0 --port 8000
