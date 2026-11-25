import {
  readFileSync,
  writeFileSync,
  mkdirSync,
  readdirSync,
  existsSync,
  copyFileSync,
  rmSync,
  renameSync,
} from "node:fs";
import { resolve, join, delimiter } from "node:path";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

process.chdir(fileURLToPath(new URL("../", import.meta.url)));
const matrix = JSON.parse(readFileSync("tools/releases.json"));
const verify = process.argv.includes("--matrix");
const output = resolve("build/classes");
rmSync(output, { recursive: true, force: true });
for (const p of ["build/deps", "dist", output])
  mkdirSync(p, { recursive: true });
function jdk(tool) {
  return process.env.JAVA_HOME
    ? join(process.env.JAVA_HOME, "bin", tool)
    : tool;
}
function files(dir) {
  return readdirSync(dir, { withFileTypes: true })
    .flatMap((e) =>
      e.isDirectory() ? files(join(dir, e.name)) : join(dir, e.name),
    )
    .sort();
}
function dependency(name, url, hash, algo = "sha256") {
  const dest = resolve("build/deps", name + ".jar");
  if (!existsSync(dest))
    execFileSync("curl", ["-fsSL", "--retry", "3", url, "-o", dest]);
  if (createHash(algo).update(readFileSync(dest)).digest("hex") !== hash)
    throw Error("Incorrect checksum: " + dest);
  return dest;
}
const mixin = dependency(
  "mixin",
  "https://maven.fabricmc.net/net/fabricmc/sponge-mixin/0.15.3+mixin.0.8.7/sponge-mixin-0.15.3+mixin.0.8.7.jar",
  "0a7735e98905e445a7ddb0eef617ebfb5166ac95c293206cce4241aab7be56fd",
);
const brigadier = dependency(
  "brigadier",
  "https://libraries.minecraft.net/com/mojang/brigadier/1.0.18/brigadier-1.0.18.jar",
  "edc4926aa4b49010f6e7ac46efd623fb38f9517344d26f6251d79a26a9738c0b",
);
const slf4j = dependency(
  "slf4j",
  "https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/2.0.1/slf4j-api-2.0.1.jar",
  "b36b99b8d99ea7857554d9b25dfdbd1fb25378c926f7b9e0249983c99335b2c4",
);
const base = matrix.find(
  (r) => r.minecraft === "1.20.1" && r.loader === "fabric",
);
function compile(r, dest) {
  const jar = dependency(
    (r.legacyForge ? "forge" : r.loader) + "-" + r.chunky,
    r.url,
    r.sha512,
    "sha512",
  );
  execFileSync(
    jdk("javac"),
    [
      "--release",
      "17",
      "-g:none",
      "-encoding",
      "UTF-8",
      "-proc:none",
      "-cp",
      [mixin, brigadier, slf4j, jar].join(delimiter),
      "-d",
      dest,
      ...files("src/main/java"),
    ],
    { stdio: "inherit" },
  );
  return jar;
}
const baseline = compile(base, output);
if (verify) {
  const seen = new Set();
  for (const r of matrix)
    if (r.url && !seen.has(r.url)) {
      compile(r, "build/abi");
      for (const file of files(output)) {
        if (
          !readFileSync(file).equals(
            readFileSync(join("build/abi", file.slice(output.length + 1))),
          )
        )
          throw Error(
            "Binary interface differs for " +
              r.loader +
              " " +
              r.chunky +
              " in " +
              file,
          );
      }
      seen.add(r.url);
      console.log("Verified identical classes against", r.loader, r.chunky);
    }
}
const checkPath = [
  baseline,
  output,
  "build/checks",
  slf4j,
  mixin,
  brigadier,
].join(delimiter);
execFileSync(
  jdk("javac"),
  [
    "--release",
    "17",
    "-proc:none",
    "-cp",
    checkPath,
    "-d",
    "build/checks",
    "tools/Checks.java",
  ],
  { stdio: "inherit" },
);
execFileSync(jdk("java"), ["-cp", checkPath, "dev.kitsuya.offline.Checks"], {
  stdio: "inherit",
});
for (const f of files("src/main/resources")) {
  const dest = resolve(output, f.slice("src/main/resources".length + 1));
  mkdirSync(join(dest, ".."), { recursive: true });
  copyFileSync(f, dest);
}
copyFileSync(
  join(output, "META-INF", "mods.toml"),
  join(output, "META-INF", "neoforge.mods.toml"),
);
writeFileSync(
  join(output, "chunky-versions.txt"),
  [...new Set(matrix.map((r) => r.chunky).filter(Boolean))].sort().join("\n"),
);
copyFileSync("LICENSE", join(output, "LICENSE"));
writeFileSync(
  join(output, "META-INF/MANIFEST.MF"),
  "Manifest-Version: 1.0\nMixinConfigs: kits_chunky_offline.mixins.json\n\n",
);
execFileSync(
  jdk("jar"),
  [
    "--create",
    "--file",
    resolve("build/kits_chunky_offline.tmp.jar"),
    "--date=2026-01-01T00:00:00Z",
    "--no-manifest",
    ...files(output).map((f) => f.slice(output.length + 1)),
  ],
  { cwd: output, stdio: "inherit" },
);
renameSync(
  "build/kits_chunky_offline.tmp.jar",
  "dist/kits_chunky_offline-2.0.1.jar",
);
console.log("Built dist/kits_chunky_offline-2.0.1.jar");
