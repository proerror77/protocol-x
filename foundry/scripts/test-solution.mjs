#!/usr/bin/env node
/**
 * Unit tests for TOC Foundry Solution implementation handlers, Scenario decisions,
 * and manifest/package digest verification.
 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { register } from '../implementation.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

async function main() {
  const manifest = JSON.parse(await readFile(resolve(ROOT, 'manifest.json'), 'utf8'));
  const pkg = JSON.parse(await readFile(resolve(ROOT, manifest.definition), 'utf8'));
  const implBytes = await readFile(resolve(ROOT, manifest.implementation.file));

  assert.equal(manifest.format, 'ontology-installation-v1');
  assert.equal(pkg.format, 'eigos-ontology-package-v1');
  assert.equal(manifest.definitionDigest, pkg.digest);
  assert.equal(
    createHash('sha256').update(implBytes).digest('hex'),
    manifest.implementation.sha256,
  );

  const reg = await register({});

  // 1. Reception window calculation
  const win = await reg.handlers.get('CalculateReceptionWindow')({
    flightStatus: 'estimated',
    anchorIso: '2026-11-28T10:00:00.000Z',
    sourceTimeIso: '2026-11-28T09:30:00.000Z',
  });
  assert.equal(win.windowFrom, '2026-11-28T10:45:00.000Z');
  assert.equal(win.windowTo, '2026-11-28T11:15:00.000Z');
  assert.equal(win.validUntil, '2026-11-28T09:40:00.000Z');

  // At-gate skips taxi minutes
  const atGate = await reg.handlers.get('CalculateReceptionWindow')({
    flightStatus: 'atGate',
    anchorIso: '2026-11-28T10:00:00.000Z',
    sourceTimeIso: '2026-11-28T10:00:00.000Z',
  });
  assert.equal(atGate.windowFrom, '2026-11-28T10:35:00.000Z');
  assert.equal(atGate.validUntil, null);

  // 2. Hotel late risk evaluation (11:15 UTC = 19:15 HKT, +45m = 20:00 HKT vs 19:30 cutoff)
  const risk = await reg.handlers.get('EvaluateHotelRisk')({
    windowToIso: '2026-11-28T11:15:00.000Z',
    transferMinutes: 45,
    latestCheckInHhMm: '19:30',
    hotelConfirmed: true,
  });
  assert.equal(risk.lateRisk, true);
  assert.equal(risk.requiresRoomHoldAlert, true);

  // 3. Capacity check
  assert.equal(
    await reg.handlers.get('CheckVehicleCapacity')({
      requiredCapacity: 4,
      resourceCapacity: 6,
      resourceReadiness: 'available',
    }),
    true,
  );
  assert.equal(
    await reg.handlers.get('CheckVehicleCapacity')({
      requiredCapacity: 4,
      resourceCapacity: 6,
      resourceReadiness: 'unavailable',
    }),
    false,
  );

  // 4. Machinery transition guard (checkedIn requires hotelConfirmed=true)
  assert.equal(
    await reg.handlers.get('CanTransitionReceptionStage')({
      currentStage: 'pending',
      targetStage: 'met',
      hotelConfirmed: false,
    }),
    true,
  );
  assert.equal(
    await reg.handlers.get('CanTransitionReceptionStage')({
      currentStage: 'hotelHandoff',
      targetStage: 'checkedIn',
      hotelConfirmed: false,
    }),
    false,
  );
  assert.equal(
    await reg.handlers.get('CanTransitionReceptionStage')({
      currentStage: 'hotelHandoff',
      targetStage: 'checkedIn',
      hotelConfirmed: true,
    }),
    true,
  );

  // 5. Scenario decision adapter
  const parsed = reg.scenarioDecisions.parseDecision({ strategy: 'move', note: 'Use spare van B02' });
  assert.equal(parsed.strategy, 'move');
  assert.throws(() => reg.scenarioDecisions.parseDecision({ strategy: 'auto_override' }));

  console.log('ALL TESTS PASSED: toc-foundry-solution');
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
