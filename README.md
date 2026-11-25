# Kit's Chunky Offline

A dedicated-server mod that uses Chunky to pregenerate terrain while the server is empty.

## Build

Install JDK 25, Node.js 22 or later, and curl. Set `JAVA_HOME` to the JDK directory and run the following command.

```sh
node tools/build.mjs
```

The build downloads checksum-verified dependencies, compiles the mod, runs its checks and writes `dist/kits_chunky_offline-2.0.1.jar`. To verify compilation against every pinned Chunky dependency, run `node tools/build.mjs --matrix`.
