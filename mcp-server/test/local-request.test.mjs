import { test } from "node:test";
import assert from "node:assert/strict";

// Runs against the compiled server: npm run build first (npm test does).
import { isLocalRequest } from "../dist/local-request.js";

const PORT = 25610;

test("accepts the loopback names of this server without an Origin", () => {
  for (const host of ["127.0.0.1:25610", "localhost:25610", "[::1]:25610", "LOCALHOST:25610"]) {
    assert.equal(isLocalRequest({ host }, PORT), true, host);
  }
});

test("refuses other hosts (DNS rebinding), a missing Host and other ports", () => {
  for (const host of [undefined, "evil.example:25610", "localhost.evil.example:25610", "127.0.0.1", "127.0.0.1:80"]) {
    assert.equal(isLocalRequest({ host }, PORT), false, String(host));
  }
});

test("accepts only this same server as a browser Origin", () => {
  assert.equal(isLocalRequest({ host: "127.0.0.1:25610", origin: "http://127.0.0.1:25610" }, PORT), true);
  assert.equal(isLocalRequest({ host: "127.0.0.1:25610", origin: "http://localhost:25610" }, PORT), true);
  for (const origin of ["http://evil.example", "null", "http://127.0.0.1:3000", "https://127.0.0.1:25610"]) {
    assert.equal(isLocalRequest({ host: "127.0.0.1:25610", origin }, PORT), false, origin);
  }
});
