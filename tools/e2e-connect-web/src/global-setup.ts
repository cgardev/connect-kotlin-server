import { spawn, type ChildProcess } from "node:child_process";
import { readdirSync, statSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "../../..");

const port = process.env.E2E_PORT ?? "8088";
const baseUrl = process.env.E2E_BASE_URL ?? `http://localhost:${port}`;
const javaBin = process.env.E2E_JAVA ?? "java";

let server: ChildProcess | undefined;

/**
 * Boots the example server before the suite and tears it down afterwards, so the
 * tests exercise a real, running Connect API. Point E2E_BASE_URL at an external
 * instance to skip the embedded launch.
 */
export async function setup() {
  process.env.E2E_BASE_URL = baseUrl;

  if (process.env.E2E_BASE_URL) {
    // An external server was requested: just wait for it to be reachable.
    if (process.env.E2E_SKIP_LAUNCH === "1") {
      await waitForHealth(baseUrl);
      return;
    }
  }

  const jar = await ensureBootJar();
  server = spawn(javaBin, ["-jar", jar, `--connect.server.port=${port}`], {
    cwd: repoRoot,
    stdio: "inherit",
  });
  server.on("exit", (code) => {
    if (code && code !== 0 && code !== 143) {
      console.error(`server exited unexpectedly with code ${code}`);
    }
  });

  await waitForHealth(baseUrl);
}

export async function teardown() {
  if (server && !server.killed) {
    server.kill("SIGTERM");
  }
}

/** Builds the Spring Boot jar (unless skipped) and returns the freshest one. */
async function ensureBootJar(): Promise<string> {
  const libsDir = resolve(repoRoot, "project/app-server-spring/build/libs");
  if (process.env.E2E_SKIP_BUILD !== "1") {
    await run(resolve(repoRoot, "gradlew"), [":project:app-server-spring:bootJar", "-q"]);
  }
  const jar = findBootJar(libsDir);
  if (!jar) throw new Error(`Boot jar not found in ${libsDir}`);
  return jar;
}

/** The most recently built runnable (non "-plain") boot jar. */
function findBootJar(libsDir: string): string | undefined {
  let entries: string[];
  try {
    entries = readdirSync(libsDir);
  } catch {
    return undefined;
  }
  return entries
    .filter((f) => f.endsWith(".jar") && !f.endsWith("-plain.jar"))
    .map((f) => resolve(libsDir, f))
    .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)[0];
}

function run(command: string, args: string[]): Promise<void> {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, { cwd: repoRoot, stdio: "inherit" });
    child.on("error", reject);
    child.on("exit", (code) =>
      code === 0 ? resolvePromise() : reject(new Error(`${command} exited with ${code}`)),
    );
  });
}

async function waitForHealth(url: string, timeoutMs = 90_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${url}/healthz`);
      if (res.ok) return;
    } catch {
      // not up yet
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`Server at ${url} did not become healthy within ${timeoutMs}ms`);
}
