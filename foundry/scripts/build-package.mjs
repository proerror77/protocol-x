#!/usr/bin/env node
/**
 * Builds deterministic Foundry installation artifacts (`toc-ontology.package.json`
 * and `manifest.json`) using MDN Foundry's exact `canonical()` SHA-256 digest algorithm,
 * and validates internal referential integrity against Foundry's Ontology rules.
 */
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tocOntologyDocument } from '../src/ontology.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const ONTOLOGY_NAME_RE = /^[A-Za-z][A-Za-z0-9]*$/;

// Exact mirror of MDN-Man-Do-Nothing-Foundry/packages/core/identity.ts
function canonical(value) {
  const normalize = (item) => {
    if (Array.isArray(item)) return item.map(normalize);
    if (item && typeof item === 'object') {
      return Object.fromEntries(
        Object.entries(item)
          .filter(([, v]) => v !== undefined)
          .sort(([left], [right]) => left.localeCompare(right, 'en'))
          .map(([key, v]) => [key, normalize(v)]),
      );
    }
    return item;
  };
  return JSON.stringify(normalize(value));
}

function digest(value) {
  return createHash('sha256').update(canonical(value)).digest('hex');
}

function assertName(name, ctx) {
  if (typeof name !== 'string' || !ONTOLOGY_NAME_RE.test(name) || name.length > 80) {
    throw new Error(`Invalid ontologyName "${name}" at ${ctx}`);
  }
}

function validateOntologyDocument(doc) {
  assertName(doc.id, 'document.id');
  const objects = new Map(doc.objectTypes.map((o) => [o.apiName, o]));
  const interfaces = new Map(doc.interfaceTypes.map((i) => [i.apiName, i]));
  const links = new Map(doc.linkTypes.map((l) => [l.apiName, l]));
  const functions = new Map(doc.functionTypes.map((f) => [f.apiName, f]));
  const actions = new Map(doc.actionTypes.map((a) => [a.apiName, a]));

  if (objects.size !== doc.objectTypes.length) throw new Error('Duplicate objectType apiName');
  if (interfaces.size !== doc.interfaceTypes.length) throw new Error('Duplicate interfaceType apiName');
  if (links.size !== doc.linkTypes.length) throw new Error('Duplicate linkType apiName');
  if (functions.size !== doc.functionTypes.length) throw new Error('Duplicate functionType apiName');
  if (actions.size !== doc.actionTypes.length) throw new Error('Duplicate actionType apiName');

  // Check objectTypes, properties, interfaces, machinery
  for (const obj of doc.objectTypes) {
    assertName(obj.apiName, `objectType.${obj.apiName}`);
    if (!obj.properties[obj.primaryKey]) throw new Error(`Missing primaryKey on ${obj.apiName}`);
    if (!obj.properties[obj.titleProperty]) throw new Error(`Missing titleProperty on ${obj.apiName}`);
    for (const propName of Object.keys(obj.properties)) {
      assertName(propName, `${obj.apiName}.properties.${propName}`);
    }
    for (const [ifaceName, mapping] of Object.entries(obj.interfaces)) {
      const iface = interfaces.get(ifaceName);
      if (!iface) throw new Error(`Unknown interface ${ifaceName} on ${obj.apiName}`);
      for (const [alias, localProp] of Object.entries(mapping.properties)) {
        if (!iface.properties[alias]) {
          throw new Error(`Unknown interface property ${ifaceName}.${alias} on ${obj.apiName}`);
        }
        if (!obj.properties[localProp]) {
          throw new Error(`Missing mapped property ${obj.apiName}.${localProp}`);
        }
      }
      for (const [alias, actionMap] of Object.entries(mapping.actions)) {
        if (!iface.actions[alias]) throw new Error(`Unknown interface action ${ifaceName}.${alias}`);
        const act = actions.get(actionMap.action);
        if (!act) throw new Error(`Unknown mapped action ${actionMap.action} on ${obj.apiName}`);
        const expectedReq = iface.actions[alias].parameters.required ?? [];
        for (const reqParam of expectedReq) {
          if (!actionMap.parameters[reqParam]) {
            throw new Error(`Missing required interface parameter ${reqParam} on ${obj.apiName}.${alias}`);
          }
        }
      }
    }
    if (obj.machinery) {
      const logClass = obj.typeClasses?.find((c) => c.kind === 'machinery-log');
      const propClass = obj.typeClasses?.find((c) => c.kind === 'machinery-property');
      if (!logClass || !propClass) {
        throw new Error(`Machinery on ${obj.apiName} requires machinery-log and machinery-property typeClasses`);
      }
      const targetObj = objects.get(logClass.name);
      const stateEnum = targetObj?.properties[propClass.name]?.schema?.enum;
      if (!Array.isArray(stateEnum)) {
        throw new Error(`Machinery target ${logClass.name}.${propClass.name} must have string enum`);
      }
      const stateSet = new Set(obj.machinery.states.map((s) => s.name));
      for (const s of stateSet) {
        if (!stateEnum.includes(s)) throw new Error(`Machinery state ${s} not in ${logClass.name}.${propClass.name} enum`);
      }
      for (const t of obj.machinery.transitions) {
        if (t.action && !actions.has(t.action)) throw new Error(`Unknown machinery action ${t.action}`);
      }
    }
  }

  // Check links
  for (const link of doc.linkTypes) {
    assertName(link.apiName, `linkType.${link.apiName}`);
    if (!objects.has(link.from.type)) throw new Error(`Unknown link.from.type ${link.from.type}`);
    if (!objects.has(link.to.type)) throw new Error(`Unknown link.to.type ${link.to.type}`);
  }

  // Check actions & criteria
  for (const act of doc.actionTypes) {
    assertName(act.apiName, `actionType.${act.apiName}`);
    for (const [param, targetType] of Object.entries(act.objectParameters)) {
      if (!objects.has(targetType) && !interfaces.has(targetType)) {
        throw new Error(`Unknown objectParameter type ${targetType} on ${act.apiName}.${param}`);
      }
    }
    for (const crit of act.submissionCriteria) {
      if (!functions.has(crit.function)) {
        throw new Error(`Unknown submissionCriteria function ${crit.function} on ${act.apiName}`);
      }
    }
  }

  // Check agentProfiles
  for (const profile of doc.agentProfiles ?? []) {
    assertName(profile.apiName, `agentProfile.${profile.apiName}`);
    for (const o of profile.objects ?? []) if (!objects.has(o)) throw new Error(`Unknown profile object ${o}`);
    for (const i of profile.interfaces ?? []) if (!interfaces.has(i)) throw new Error(`Unknown profile interface ${i}`);
    for (const l of profile.links ?? []) if (!links.has(l)) throw new Error(`Unknown profile link ${l}`);
    for (const f of profile.functions ?? []) if (!functions.has(f)) throw new Error(`Unknown profile function ${f}`);
    for (const a of profile.actions ?? []) if (!actions.has(a)) throw new Error(`Unknown profile action ${a}`);
  }
}

async function main() {
  validateOntologyDocument(tocOntologyDocument);

  const definitionDigest = digest(tocOntologyDocument);
  const pkg = {
    format: 'eigos-ontology-package-v1',
    ontology: tocOntologyDocument.id,
    version: tocOntologyDocument.version,
    digest: definitionDigest,
    document: tocOntologyDocument,
  };

  const implPath = resolve(ROOT, 'implementation.mjs');
  const implBytes = await readFile(implPath);
  const implSha256 = createHash('sha256').update(implBytes).digest('hex');

  const manifest = {
    format: 'ontology-installation-v1',
    allowReviewedDefinitions: true,
    definition: 'toc-ontology.package.json',
    definitionDigest,
    publicSkills: [
      {
        id: 'toc-dispatch-copilot',
        name: 'TOC Dispatch Copilot',
        description: 'Read-only operational awareness, reception window calculation, and HITL Scenario proposals for Project TOC',
        tags: ['toc', 'dispatch', 'golf', 'conference', 'hitl'],
      },
    ],
    implementation: {
      file: 'implementation.mjs',
      sha256: implSha256,
    },
  };

  await writeFile(
    resolve(ROOT, 'toc-ontology.package.json'),
    `${JSON.stringify(pkg, null, 2)}\n`,
    'utf8',
  );
  await writeFile(
    resolve(ROOT, 'manifest.json'),
    `${JSON.stringify(manifest, null, 2)}\n`,
    'utf8',
  );

  // Verify implementation bundle loads cleanly and matches installation.ts contract
  const implMod = await import(implPath);
  if (typeof implMod.register !== 'function') {
    throw new Error('implementation.mjs must export register()');
  }
  const reg = await implMod.register({});
  for (const fn of tocOntologyDocument.functionTypes) {
    if (!reg.handlers.has(fn.apiName)) {
      throw new Error(`Missing handler implementation for function ${fn.apiName}`);
    }
  }

  console.log('OK: toc-foundry-solution built and verified');
  console.log(`  ontology:         ${pkg.ontology}@${pkg.version}`);
  console.log(`  definitionDigest: ${definitionDigest}`);
  console.log(`  implSha256:       ${implSha256}`);
  console.log(`  objectTypes:      ${tocOntologyDocument.objectTypes.length}`);
  console.log(`  interfaceTypes:   ${tocOntologyDocument.interfaceTypes.length}`);
  console.log(`  linkTypes:        ${tocOntologyDocument.linkTypes.length}`);
  console.log(`  functionTypes:    ${tocOntologyDocument.functionTypes.length}`);
  console.log(`  actionTypes:      ${tocOntologyDocument.actionTypes.length}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
