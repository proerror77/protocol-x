/**
 * Project TOC — MDN Foundry Implementation Bundle (`implementation.mjs`)
 *
 * Loaded by `@mdn-foundry/engine` via `ontology-installation-v1` manifest
 * (`packages/runtime/installation.ts`), verified by SHA-256 digest.
 *
 * Exports `register({ PARequests })` returning:
 * - `handlers`: Pure read-only Function implementations
 * - `effects`: Outbox side-effect dispatchers to TOC Edge services (RNS / TAK / ChannelFeed)
 * - `scenarioDecisions`: HITL dispatcher decision validator for Scenario approvals
 * - `agentInstructions`: Hard safety guardrails for Foundry AgentDriver / MCP / A2A
 */

const LEGAL_RECEPTION_TRANSITIONS = new Map([
  ['pending', 'met'],
  ['met', 'boarded'],
  ['boarded', 'hotelHandoff'],
  ['hotelHandoff', 'checkedIn'],
]);

function addMinutesIso(isoString, minutes) {
  const date = new Date(isoString);
  if (Number.isNaN(date.getTime())) throw new Error(`Invalid ISO timestamp: ${isoString}`);
  return new Date(date.getTime() + minutes * 60_000).toISOString();
}

function parseHkCutoff(referenceIso, hhMm) {
  const match = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(hhMm);
  if (!match) return null;
  // Project reference date into Asia/Hong_Kong (UTC+8)
  const ref = new Date(referenceIso);
  const hkMs = ref.getTime() + 8 * 3600_000;
  const hkDate = new Date(hkMs);
  const y = hkDate.getUTCFullYear();
  const m = String(hkDate.getUTCMonth() + 1).padStart(2, '0');
  const d = String(hkDate.getUTCDate()).padStart(2, '0');
  return new Date(`${y}-${m}-${d}T${match[1]}:${match[2]}:00+08:00`);
}

async function postEdgeBridge(path, payload) {
  const baseUrl = (process.env.TOC_EDGE_BRIDGE_URL || '').trim().replace(/\/+$/, '');
  if (!baseUrl) {
    // Safe no-op in isolated test/rehearsal mode; Outbox records delivery metadata.
    return { delivered: false, mode: 'dry_run', path };
  }
  const token = (process.env.TOC_EDGE_BRIDGE_TOKEN || '').trim();
  const res = await fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    const detail = await res.text().catch(() => '');
    throw new Error(`TOC Edge Bridge ${path} failed (${res.status}): ${detail.slice(0, 300)}`);
  }
  return res.json().catch(() => ({ delivered: true }));
}

export async function register() {
  const handlers = new Map([
    [
      'CalculateReceptionWindow',
      async (args) => {
        const {
          flightStatus,
          anchorIso,
          sourceTimeIso,
          taxiMinutes = 10,
          exitMinMinutes = 35,
          exitMaxMinutes = 65,
          freshMinutes = 10,
        } = args;
        if (['cancelled', 'delayed', 'other'].includes(flightStatus)) {
          return { windowFrom: null, windowTo: null, validUntil: null, basis: flightStatus };
        }
        const effectiveTaxi = flightStatus === 'atGate' ? 0 : taxiMinutes;
        return {
          windowFrom: addMinutesIso(anchorIso, effectiveTaxi + exitMinMinutes),
          windowTo: addMinutesIso(anchorIso, effectiveTaxi + exitMaxMinutes),
          validUntil: ['scheduled', 'estimated'].includes(flightStatus)
            ? addMinutesIso(sourceTimeIso, freshMinutes)
            : null,
          basis: flightStatus,
        };
      },
    ],
    [
      'EvaluateHotelRisk',
      async (args) => {
        const { windowToIso, transferMinutes, latestCheckInHhMm = null, hotelConfirmed } = args;
        const estimatedHotelArrivalIso = addMinutesIso(windowToIso, transferMinutes);
        if (!latestCheckInHhMm) {
          return { lateRisk: false, requiresRoomHoldAlert: false, estimatedHotelArrivalIso };
        }
        const cutoff = parseHkCutoff(estimatedHotelArrivalIso, latestCheckInHhMm);
        const lateRisk = cutoff !== null && new Date(estimatedHotelArrivalIso).getTime() > cutoff.getTime();
        return {
          lateRisk,
          requiresRoomHoldAlert: lateRisk && Boolean(hotelConfirmed),
          estimatedHotelArrivalIso,
        };
      },
    ],
    [
      'CheckVehicleCapacity',
      async (args) => {
        const { requiredCapacity, resourceCapacity, resourceReadiness } = args;
        return resourceReadiness === 'available' && Number(resourceCapacity) >= Number(requiredCapacity);
      },
    ],
    [
      'CanTransitionReceptionStage',
      async (args) => {
        const { currentStage, targetStage, hotelConfirmed } = args;
        if (LEGAL_RECEPTION_TRANSITIONS.get(currentStage) !== targetStage) return false;
        if (targetStage === 'checkedIn' && !hotelConfirmed) return false;
        return true;
      },
    ],
  ]);

  const effects = new Map([
    [
      'DispatchTakCot',
      async (payload) => postEdgeBridge('/internal/foundry-effects/tak-cot', payload),
    ],
    [
      'DispatchRnsEnvelope',
      async (payload) => postEdgeBridge('/internal/foundry-effects/rns-envelope', payload),
    ],
    [
      'NotifyChannelFeed',
      async (payload) => postEdgeBridge('/internal/foundry-effects/channel-feed', payload),
    ],
  ]);

  // Installed Scenario decision adapter (`packages/runtime/installation.ts`)
  const scenarioDecisions = {
    parseDecision(raw) {
      if (raw == null) return { strategy: 'approved', note: '' };
      if (typeof raw !== 'object' || Array.isArray(raw)) {
        throw new Error('Scenario approval decision must be an object');
      }
      const strategy = typeof raw.strategy === 'string' ? raw.strategy : 'approved';
      if (!['approved', 'wait', 'reserve', 'move'].includes(strategy)) {
        throw new Error(`Unsupported TOC scenario decision strategy: ${strategy}`);
      }
      const note = typeof raw.note === 'string' ? raw.note.slice(0, 300) : '';
      return { strategy, note };
    },
    async inspect(_context, scenario) {
      return {
        scenarioId: scenario?.id ?? null,
        revision: scenario?.revision ?? null,
        requiresHumanDispatcher: true,
      };
    },
    async capture(_context, scenario, decision) {
      return {
        capturedAt: new Date().toISOString(),
        scenarioId: scenario?.id ?? null,
        decision: decision ?? { strategy: 'approved', note: '' },
      };
    },
    async validateApproval(_context, scenario, decision) {
      if (!scenario) throw new Error('Missing Scenario for approval validation');
      if (decision && typeof decision.note === 'string' && decision.note.length > 300) {
        throw new Error('Approval note exceeds 300 characters');
      }
    },
  };

  return {
    handlers,
    effects,
    scenarioDecisions,
    agentInstructions: [
      'Observation vs Confirmed Facts: FlightObservation and TakObservation are read-only evidence and MUST NEVER automatically confirm physical reception stages (met, boarded, hotelHandoff, checkedIn).',
      'HITL Boundary: Any resource reassignment or pickup reschedule proposed by AI must be staged inside an OntologyScenario and submitted via SubmitScenarioReviewForMerge for human dispatcher approval.',
      'Event Isolation: Always scope queries and actions to the active eventId authorized in the caller Principal.',
    ],
  };
}
