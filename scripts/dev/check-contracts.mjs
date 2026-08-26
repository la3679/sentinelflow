#!/usr/bin/env node
/**
 * Validate the contracts in contracts/.
 *
 * `contracts/` is authoritative for this project, which only means something if
 * something checks it. This does four things:
 *
 *   1. Every JSON Schema compiles, and its cross-file $refs resolve.
 *   2. Every valid example validates against its envelope AND its payload
 *      schema. A schema nothing is checked against is a document, not a
 *      contract.
 *   3. Every deliberately-invalid example is REJECTED. A validator that accepts
 *      everything passes silently, so the negative cases are the ones that
 *      prove it works.
 *   4. The OpenAPI and AsyncAPI documents parse and are internally consistent.
 *
 *   bun scripts/dev/check-contracts.mjs
 *
 * Wired into `make contracts-check` and into CI.
 */

import { readFileSync, readdirSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";

import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = execFileSync("git", ["rev-parse", "--show-toplevel"], {
  cwd: HERE,
  encoding: "utf8",
}).trim();

const CONTRACTS = join(ROOT, "contracts");
const SCHEMAS = join(CONTRACTS, "schemas");
const EXAMPLES = join(CONTRACTS, "examples");

let failures = 0;
const pass = (message) => process.stdout.write(`  pass  ${message}\n`);
const fail = (message, detail) => {
  process.stdout.write(`  FAIL  ${message}\n`);
  if (detail) process.stdout.write(`        ${detail}\n`);
  failures += 1;
};

const readJson = (path) => JSON.parse(readFileSync(path, "utf8"));

// ---------------------------------------------------------------------------
// 1. Compile every schema
// ---------------------------------------------------------------------------
process.stdout.write("Contracts\n\nSchemas compile:\n");

const ajv = new Ajv2020({
  strict: true,
  allErrors: true,
  // Every schema is added up front, so a $ref to a sibling file resolves by
  // its $id without a network fetch.
  loadSchema: () => {
    throw new Error("remote schema loading is disabled; every $ref must be local");
  },
});
addFormats(ajv);

const schemaFiles = readdirSync(SCHEMAS).filter((f) => f.endsWith(".json"));

// addSchema registers a schema under the given key AND under its own $id, so
// registering it a second time under $id collides. One call is enough: the
// relative $refs between files resolve against each schema's absolute $id base.
for (const file of schemaFiles) {
  try {
    ajv.addSchema(readJson(join(SCHEMAS, file)), file);
  } catch (error) {
    fail(file, error.message);
  }
}

for (const file of schemaFiles) {
  try {
    ajv.getSchema(file) ?? ajv.compile(readJson(join(SCHEMAS, file)));
    pass(file);
  } catch (error) {
    fail(file, error.message);
  }
}

// ---------------------------------------------------------------------------
// 2 and 3. Examples
// ---------------------------------------------------------------------------

/** Which payload schema belongs to which eventType. */
const PAYLOAD_SCHEMA_BY_EVENT_TYPE = {
  "transaction.created": "transaction-created.v1.json",
  "risk.assessed": "risk-assessed.v1.json",
  "alert.created": "alert-created.v1.json",
  "alert.updated": "alert-updated.v1.json",
};

function validateEvent(label, event) {
  const envelope = ajv.getSchema("event-envelope.v1.json");
  if (!envelope(event)) {
    return { ok: false, where: "envelope", errors: envelope.errors };
  }

  const payloadSchemaFile = PAYLOAD_SCHEMA_BY_EVENT_TYPE[event.eventType];
  if (!payloadSchemaFile) {
    return {
      ok: false,
      where: "routing",
      errors: [{ message: `no schema for ${event.eventType}` }],
    };
  }

  const payload = ajv.getSchema(payloadSchemaFile);
  if (!payload(event.payload)) {
    return { ok: false, where: `payload (${payloadSchemaFile})`, errors: payload.errors };
  }

  return { ok: true };
}

const format = (errors) =>
  (errors ?? [])
    .slice(0, 3)
    .map((e) => `${e.instancePath || "/"} ${e.message}`)
    .join("; ");

process.stdout.write("\nValid examples are accepted:\n");

for (const file of readdirSync(EXAMPLES).filter((f) => f.endsWith(".json"))) {
  const path = join(EXAMPLES, file);
  const instance = readJson(path);

  // The DLQ record is not an envelope; it wraps one.
  if (file.startsWith("dlq-record")) {
    const dlq = ajv.getSchema("dlq-record.v1.json");
    if (dlq(instance)) pass(file);
    else fail(file, format(dlq.errors));
    continue;
  }

  const result = validateEvent(file, instance);
  if (result.ok) pass(`${file} (${instance.eventType})`);
  else fail(`${file} — rejected at ${result.where}`, format(result.errors));
}

process.stdout.write("\nInvalid examples are rejected:\n");

const INVALID = join(EXAMPLES, "invalid");
if (existsSync(INVALID)) {
  for (const file of readdirSync(INVALID).filter((f) => f.endsWith(".json"))) {
    const instance = readJson(join(INVALID, file));

    // A bare payload rather than a full envelope: validate it directly.
    const isBarePayload = instance.eventType === undefined;
    let rejected;
    if (isBarePayload) {
      const guess = file.startsWith("alert-created") ? "alert-created.v1.json" : null;
      if (!guess) {
        fail(file, "no payload schema mapped for this invalid fixture");
        continue;
      }
      rejected = !ajv.getSchema(guess)(instance);
    } else {
      rejected = !validateEvent(file, instance).ok;
    }

    if (rejected) pass(`${file} correctly rejected`);
    else fail(`${file} was ACCEPTED — the schema does not constrain what it claims to`);
  }
}

// ---------------------------------------------------------------------------
// 4. OpenAPI and AsyncAPI
// ---------------------------------------------------------------------------
process.stdout.write("\nAPI documents parse:\n");

const openapiPath = join(CONTRACTS, "openapi", "sentinelflow-api.yaml");
if (existsSync(openapiPath)) {
  try {
    const SwaggerParser = (await import("@apidevtools/swagger-parser")).default;
    const api = await SwaggerParser.validate(openapiPath);
    pass(`openapi/sentinelflow-api.yaml — ${api.info.title} ${api.info.version}`);
  } catch (error) {
    fail("openapi/sentinelflow-api.yaml", error.message.split("\n")[0]);
  }
} else {
  fail("openapi/sentinelflow-api.yaml", "missing");
}

const asyncapiPath = join(CONTRACTS, "asyncapi", "sentinelflow-events.yaml");
if (existsSync(asyncapiPath)) {
  try {
    // fromFile, not parse(string): the document $refs ../schemas/*.json, and a
    // parser handed only the contents has no base path to resolve them against.
    const { Parser, fromFile } = await import("@asyncapi/parser");
    const parser = new Parser();
    const { document, diagnostics } = await fromFile(parser, asyncapiPath).parse();
    const errors = diagnostics.filter((d) => d.severity === 0);
    if (!document || errors.length > 0) {
      fail(
        "asyncapi/sentinelflow-events.yaml",
        errors.map((e) => e.message).join("; ") || "no document produced",
      );
    } else {
      pass(
        `asyncapi/sentinelflow-events.yaml — ${document.info().title()} ${document.info().version()}`,
      );
    }
  } catch (error) {
    fail("asyncapi/sentinelflow-events.yaml", error.message.split("\n")[0]);
  }
} else {
  fail("asyncapi/sentinelflow-events.yaml", "missing");
}

// ---------------------------------------------------------------------------
process.stdout.write("\n");
if (failures > 0) {
  process.stdout.write(`${failures} contract check(s) FAILED.\n`);
  process.exit(1);
}
process.stdout.write("All contract checks passed.\n");
