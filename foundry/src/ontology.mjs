/**
 * Project TOC — MDN Man Do Nothing Foundry OntologyDocument (v1.0.0)
 *
 * Strictly conforms to `@mdn-foundry/engine` v0.2.1 (`packages/ontology/schema.ts`,
 * `packages/ontology/model.ts`, `packages/ontology/process-definition.ts`,
 * `packages/ontology/scenario-definition.ts`).
 *
 * Design principles:
 * 1. Dual-domain reuse via `WorkItem` + `ReservableResource` Interfaces (Golf & Conference).
 * 2. Declarative `Machinery` on `ArrivalPlan.receptionStage` via `ArrivalPlanReceptionStageLog`
 *    (eliminates hardcoded `Literal["met","boarded","hotel_handoff","checked_in"]`).
 * 3. Native Foundry `Scenario` + 5 immutable review/merge Actions for HITL what-if dispatch.
 * 4. Native `reservation` on resource assignment (`AssignVehicle` / `RebookSessionRoom`).
 * 5. Hard separation of external Observations (`FlightObservation`, `TakObservation`) from
 *    confirmed operational facts (`TransferLeg`, `ArrivalPlan`).
 */

const textProp = (description, required = true) => ({
  description,
  schema: { type: 'string' },
  required,
});

const nullableTextProp = (description, required = false) => ({
  description,
  schema: { type: ['string', 'null'] },
  required,
});

const dateTimeProp = (description, required = false) => ({
  description,
  schema: { type: 'string', format: 'date-time' },
  required,
});

const intProp = (description, minimum = 0, required = true) => ({
  description,
  schema: { type: 'integer', minimum },
  required,
});

const boolProp = (description, required = true) => ({
  description,
  schema: { type: 'boolean' },
  required,
});

const numberProp = (description, required = false) => ({
  description,
  schema: { type: 'number' },
  required,
});

const processLogProperties = Object.fromEntries(
  Object.entries({
    id: { type: 'string' },
    name: { type: 'string' },
    processId: { type: 'string' },
    property: { type: 'string' },
    oldState: { type: ['string', 'null'] },
    newState: { type: ['string', 'null'] },
    timestamp: { type: 'string', format: 'date-time' },
    recordedAt: { type: 'string', format: 'date-time' },
    sequence: { type: 'string' },
    action: { type: ['string', 'null'] },
    requestId: { type: ['string', 'null'] },
    taskId: { type: ['string', 'null'] },
    source: { type: ['string', 'null'] },
    actor: { type: 'string' },
    release: { type: 'string' },
  }).map(([name, schema]) => [name, { description: `Process log ${name}`, schema, required: true }]),
);

const scenarioProperties = {
  id: textProp('Scenario identifier'),
  name: textProp('Scenario name'),
  owner: textProp('Owning authenticated identity'),
  state: textProp('Proposal lifecycle state'),
  reviewStatus: {
    description: 'Review status of the current proposal revision',
    schema: { type: 'string', enum: ['draft', 'in-review', 'approved', 'rejected'] },
    required: false,
  },
  revision: {
    description: 'Revision to inspect before changing or merging the proposal',
    schema: { type: 'integer', minimum: 1 },
    required: true,
  },
  expiresAt: dateTimeProp('Expiration time', true),
  syncConflict: {
    description: 'Whether automatic synchronization observed a conflict',
    schema: { type: 'boolean' },
    required: false,
  },
};

const input = (parameter) => ({ parameter });
const lit = (literal) => ({ literal });

const baseScenarioParams = {
  id: { type: 'string', format: 'uuid' },
  revision: { type: 'integer', minimum: 1 },
};
const snapshotSchema = { type: 'string', pattern: '^[a-f0-9]{64}$' };
const approvalDecisionSchema = {
  type: 'object',
  description: 'Optional decision interpreted by the installed review adapter',
};

function makeScenarioReviewAction(apiName, description, fields, requiredFields, review) {
  return {
    apiName,
    description,
    parameters: {
      type: 'object',
      properties: { ...baseScenarioParams, ...fields },
      required: ['id', 'revision', ...requiredFields],
      additionalProperties: false,
    },
    objectParameters: {},
    submissionCriteria: [],
    rules: [
      {
        op: 'reviewScenario',
        scenario: input('id'),
        expectedRevision: input('revision'),
        review,
      },
    ],
    result: { type: 'OntologyScenario', id: input('id') },
  };
}

export const tocOntologyDocument = {
  id: 'TocOperations',
  version: '1.0.0',

  typeGroups: [
    {
      apiName: 'CoreDispatch',
      displayName: 'Core Operations & Dispatch',
      description: 'Events, personnel, work items, reservable resources, and reception plans',
      objectTypes: ['Event', 'Person', 'TransferLeg', 'Vehicle', 'ArrivalPlan', 'Session', 'Room'],
    },
    {
      apiName: 'ExternalObservations',
      displayName: 'External Observations (Read-Isolated)',
      description: 'External HKIA flight and TAK CoT observations that never overwrite confirmed facts',
      objectTypes: ['FlightObservation', 'TakObservation'],
    },
    {
      apiName: 'GovernanceAndProcess',
      displayName: 'Machinery & Scenarios',
      description: 'Process state logs and HITL scenario proposals',
      objectTypes: ['ArrivalPlanReceptionStageLog', 'OntologyScenario'],
    },
  ],

  interfaceTypes: [
    {
      apiName: 'WorkItem',
      description: 'Domain-agnostic operational work item projected to Web and Android dispatch clients',
      properties: {
        id: textProp('Unique work item identifier'),
        eventId: textProp('Owning activity/event identifier'),
        title: textProp('Display title'),
        description: textProp('Route or operational brief'),
        subjectLabel: textProp('Passenger, player, or speaker label'),
        ownerActor: textProp('Assigned operator actor ID'),
        dispatchStatus: {
          description: 'Assignment acknowledgement state',
          schema: { type: 'string', enum: ['assigned', 'acknowledged', 'declined', 'completed'] },
          required: true,
        },
        requiredCapacity: intProp('Minimum required capacity (seats)', 1, true),
        pickupAt: dateTimeProp('Optional scheduled pickup timestamp', false),
      },
      optionalProperties: ['pickupAt'],
      links: {
        assignedResource: { target: 'ReservableResource', cardinality: 'one', required: false },
      },
      actions: {
        acknowledge: {
          parameters: {
            type: 'object',
            properties: {
              item: { type: 'string', minLength: 1 },
              expectedRevision: { type: 'integer', minimum: 1 },
            },
            required: ['item', 'expectedRevision'],
            additionalProperties: false,
          },
          objectParameters: { item: 'WorkItem' },
          required: true,
        },
        decline: {
          parameters: {
            type: 'object',
            properties: {
              item: { type: 'string', minLength: 1 },
              expectedRevision: { type: 'integer', minimum: 1 },
              reason: { type: 'string', maxLength: 300 },
            },
            required: ['item', 'expectedRevision'],
            additionalProperties: false,
          },
          objectParameters: { item: 'WorkItem' },
          required: true,
        },
      },
    },
    {
      apiName: 'ReservableResource',
      description: 'Capacity-bearing operational resource (Vehicle, Room, etc.)',
      properties: {
        id: textProp('Unique resource identifier'),
        eventId: textProp('Owning activity/event identifier'),
        label: textProp('Human-readable callsign or room label'),
        capacity: intProp('Maximum capacity', 1, true),
        readiness: {
          description: 'Operational availability state',
          schema: { type: 'string', enum: ['available', 'unavailable', 'maintenance'] },
          required: true,
        },
      },
      optionalProperties: [],
      links: {},
      actions: {},
    },
    {
      apiName: 'ProcessLog',
      description: 'Observed changes to an Ontology process state',
      properties: processLogProperties,
      optionalProperties: [],
      links: {},
      actions: {},
    },
    {
      apiName: 'Scenario',
      description: 'An owned or explicitly shared Scenario proposal',
      properties: scenarioProperties,
      optionalProperties: [],
      links: {},
      actions: {},
    },
  ],

  objectTypes: [
    {
      apiName: 'Event',
      displayName: 'Operational Event',
      description: 'An isolated tournament, conference, or field operation activity',
      primaryKey: 'id',
      titleProperty: 'title',
      groups: ['CoreDispatch'],
      properties: {
        id: textProp('Event identifier (e.g. golf-demo)'),
        title: textProp('Event display title'),
        domain: {
          description: 'Operational domain template',
          schema: { type: 'string', enum: ['golf', 'conference', 'custom'] },
          required: true,
        },
        status: {
          description: 'Lifecycle status',
          schema: { type: 'string', enum: ['active', 'archived'] },
          required: true,
        },
      },
      interfaces: {},
    },
    {
      apiName: 'Person',
      displayName: 'Field Operator',
      description: 'Authenticated dispatcher, driver, greeter, or steward bound to an event',
      primaryKey: 'id',
      titleProperty: 'name',
      groups: ['CoreDispatch'],
      properties: {
        id: textProp('Person actor identifier (person-<hex>)'),
        eventId: textProp('Primary event scope'),
        name: textProp('Full name'),
        callsign: textProp('Radio / TAK callsign'),
        role: {
          description: 'Operational role',
          schema: { type: 'string', enum: ['dispatcher', 'staff'] },
          required: true,
        },
        suspended: boolProp('Whether access is currently suspended'),
      },
      interfaces: {},
    },
    {
      apiName: 'Vehicle',
      displayName: 'Shuttle Vehicle',
      description: 'Ground transport vehicle for golf/airport transfer operations',
      primaryKey: 'id',
      titleProperty: 'callsign',
      groups: ['CoreDispatch'],
      properties: {
        id: textProp('Vehicle identifier'),
        eventId: textProp('Event identifier'),
        callsign: textProp('Vehicle display callsign'),
        seats: intProp('Passenger seat capacity', 1, true),
        readiness: {
          description: 'Vehicle operational readiness',
          schema: { type: 'string', enum: ['available', 'unavailable', 'maintenance'] },
          required: true,
        },
      },
      interfaces: {
        ReservableResource: {
          properties: {
            id: 'id',
            eventId: 'eventId',
            label: 'callsign',
            capacity: 'seats',
            readiness: 'readiness',
          },
          links: {},
          actions: {},
        },
      },
    },
    {
      apiName: 'TransferLeg',
      displayName: 'Transfer Leg',
      description: 'Confirmed airport or venue ground transfer work item (Golf domain)',
      primaryKey: 'id',
      titleProperty: 'title',
      groups: ['CoreDispatch'],
      properties: {
        id: textProp('Transfer leg identifier'),
        eventId: textProp('Event identifier'),
        title: textProp('Transfer leg title'),
        route: textProp('Origin to destination route description'),
        playerName: textProp('Passenger or player group display name'),
        assignedDriver: textProp('Assigned driver actor ID'),
        dispatchState: {
          description: 'Driver acknowledgement state',
          schema: { type: 'string', enum: ['assigned', 'acknowledged', 'declined', 'completed'] },
          required: true,
        },
        passengers: intProp('Number of passengers', 1, true),
        pickupAt: dateTimeProp('Confirmed ground pickup timestamp', false),
        vehicleId: nullableTextProp('Currently assigned vehicle ID', false),
        declineReason: nullableTextProp('Optional reason if declined by operator', false),
      },
      interfaces: {
        WorkItem: {
          properties: {
            id: 'id',
            eventId: 'eventId',
            title: 'title',
            description: 'route',
            subjectLabel: 'playerName',
            ownerActor: 'assignedDriver',
            dispatchStatus: 'dispatchState',
            requiredCapacity: 'passengers',
            pickupAt: 'pickupAt',
          },
          links: {
            assignedResource: 'vehicle',
          },
          actions: {
            acknowledge: {
              action: 'AcceptTransfer',
              parameters: { item: 'transfer', expectedRevision: 'expectedRevision' },
            },
            decline: {
              action: 'DeclineTransfer',
              parameters: { item: 'transfer', expectedRevision: 'expectedRevision', reason: 'reason' },
            },
          },
        },
      },
    },
    {
      apiName: 'ArrivalPlan',
      displayName: 'Player Arrival Plan',
      description: 'Airport reception plan grouping players, hotel stay, greeter, and Machinery stage',
      primaryKey: 'id',
      titleProperty: 'title',
      groups: ['CoreDispatch'],
      properties: {
        id: textProp('Arrival plan identifier'),
        eventId: textProp('Event identifier'),
        title: textProp('Arrival plan display title'),
        flightNumber: textProp('Primary flight number (e.g. CX881)'),
        flightDate: textProp('Hong Kong local service date (YYYY-MM-DD)'),
        meetingPoint: textProp('Airport meeting point'),
        greeterActor: textProp('Assigned airport greeter actor ID'),
        driverActor: textProp('Assigned driver actor ID'),
        hotelName: textProp('Destination hotel name'),
        hotelConfirmed: boolProp('Dispatcher-confirmed hotel booking gate'),
        latestCheckIn: nullableTextProp('Latest check-in time in HK local HH:MM', false),
        transferMinutes: intProp('Estimated airport-to-hotel transfer duration in minutes', 5, true),
        receptionStage: {
          description: 'Declarative Machinery reception lifecycle stage',
          schema: {
            type: 'string',
            enum: ['pending', 'met', 'boarded', 'hotelHandoff', 'checkedIn'],
          },
          required: true,
        },
      },
      interfaces: {},
    },
    {
      apiName: 'ArrivalPlanReceptionStageLog',
      displayName: 'Arrival Plan process log',
      description: 'Committed changes to ArrivalPlan.receptionStage; proposed Scenario edits are excluded',
      primaryKey: 'id',
      titleProperty: 'name',
      groups: ['GovernanceAndProcess'],
      typeClasses: [
        { kind: 'machinery-log', name: 'ArrivalPlan' },
        { kind: 'machinery-property', name: 'receptionStage' },
      ],
      properties: processLogProperties,
      machinery: {
        states: [
          { name: 'pending', dwellSeconds: 3600 },
          { name: 'met', dwellSeconds: 1800 },
          { name: 'boarded', dwellSeconds: 5400 },
          { name: 'hotelHandoff', dwellSeconds: 1800 },
          { name: 'checkedIn', terminal: true },
        ],
        transitions: [
          {
            from: 'pending',
            to: 'met',
            action: 'AdvanceReceptionStage',
            execution: { subject: 'plan' },
          },
          {
            from: 'met',
            to: 'boarded',
            action: 'AdvanceReceptionStage',
            execution: { subject: 'plan' },
          },
          {
            from: 'boarded',
            to: 'hotelHandoff',
            action: 'AdvanceReceptionStage',
            execution: { subject: 'plan' },
          },
          {
            from: 'hotelHandoff',
            to: 'checkedIn',
            action: 'AdvanceReceptionStage',
            execution: { subject: 'plan' },
          },
        ],
        metrics: {
          node: {
            label: 'Active Plans',
            aggregation: { op: 'count' },
          },
        },
      },
      interfaces: {
        ProcessLog: {
          properties: Object.fromEntries(Object.keys(processLogProperties).map((k) => [k, k])),
          links: {},
          actions: {},
        },
      },
    },
    {
      apiName: 'Room',
      displayName: 'Conference Room',
      description: 'Reservable venue room for conference operations (dual-domain proof)',
      primaryKey: 'id',
      titleProperty: 'roomLabel',
      groups: ['CoreDispatch'],
      properties: {
        id: textProp('Room identifier'),
        eventId: textProp('Event identifier'),
        roomLabel: textProp('Room display label'),
        capacity: intProp('Room seating capacity', 1, true),
        readiness: {
          description: 'Room readiness state',
          schema: { type: 'string', enum: ['available', 'unavailable', 'maintenance'] },
          required: true,
        },
      },
      interfaces: {
        ReservableResource: {
          properties: {
            id: 'id',
            eventId: 'eventId',
            label: 'roomLabel',
            capacity: 'capacity',
            readiness: 'readiness',
          },
          links: {},
          actions: {},
        },
      },
    },
    {
      apiName: 'Session',
      displayName: 'Conference Session',
      description: 'Conference session stewardship work item implementing WorkItem identically to TransferLeg',
      primaryKey: 'id',
      titleProperty: 'sessionName',
      groups: ['CoreDispatch'],
      properties: {
        id: textProp('Session identifier'),
        eventId: textProp('Event identifier'),
        sessionName: textProp('Session title'),
        brief: textProp('Session operational brief'),
        speaker: textProp('Primary speaker name'),
        steward: textProp('Assigned steward actor ID'),
        sessionState: {
          description: 'Steward acknowledgement state',
          schema: { type: 'string', enum: ['assigned', 'acknowledged', 'declined', 'completed'] },
          required: true,
        },
        attendees: intProp('Expected attendee count', 1, true),
        roomId: nullableTextProp('Assigned room ID', false),
        declineReason: nullableTextProp('Optional reason if declined by steward', false),
      },
      interfaces: {
        WorkItem: {
          properties: {
            id: 'id',
            eventId: 'eventId',
            title: 'sessionName',
            description: 'brief',
            subjectLabel: 'speaker',
            ownerActor: 'steward',
            dispatchStatus: 'sessionState',
            requiredCapacity: 'attendees',
          },
          links: {
            assignedResource: 'room',
          },
          actions: {
            acknowledge: {
              action: 'AcceptSessionStewardship',
              parameters: { item: 'session', expectedRevision: 'expectedRevision' },
            },
            decline: {
              action: 'DeclineSessionStewardship',
              parameters: { item: 'session', expectedRevision: 'expectedRevision', reason: 'reason' },
            },
          },
        },
      },
    },
    {
      apiName: 'FlightObservation',
      displayName: 'HKIA Flight Observation',
      description: 'External airport arrival observation; strictly isolated from confirmed TransferLeg facts',
      primaryKey: 'id',
      titleProperty: 'flightNumber',
      groups: ['ExternalObservations'],
      properties: {
        id: textProp('Observation key (airport:date:flight)'),
        eventId: textProp('Event identifier'),
        flightNumber: textProp('Primary flight number'),
        serviceDate: textProp('Local service date (YYYY-MM-DD)'),
        flightStatus: {
          description: 'External airport status',
          schema: {
            type: 'string',
            enum: ['scheduled', 'estimated', 'delayed', 'landed', 'atGate', 'cancelled', 'other'],
          },
          required: true,
        },
        scheduledAt: dateTimeProp('Scheduled arrival time', false),
        estimatedAt: dateTimeProp('Estimated arrival time', false),
        actualAt: dateTimeProp('Actual landing or gate time', false),
        sourceTime: dateTimeProp('Airport source publication timestamp', true),
        recordedAt: dateTimeProp('Server ingestion timestamp', true),
      },
      interfaces: {},
    },
    {
      apiName: 'TakObservation',
      displayName: 'TAK CoT Observation',
      description: 'External ATAK/OpenTAKServer CoT 2.0 observation; never mutates confirmed business facts',
      primaryKey: 'id',
      titleProperty: 'cotUid',
      groups: ['ExternalObservations'],
      properties: {
        id: textProp('Observation ID (takobs-<hex>)'),
        eventId: textProp('Event identifier'),
        cotUid: textProp('CoT event UID'),
        cotType: textProp('CoT event type string'),
        category: {
          description: 'Normalized CoT category',
          schema: { type: 'string', enum: ['position', 'geochat', 'marker', 'opsPtt', 'other'] },
          required: true,
        },
        callsign: nullableTextProp('Sender callsign', false),
        latitude: numberProp('WGS84 latitude', false),
        longitude: numberProp('WGS84 longitude', false),
        observedAt: dateTimeProp('CoT event timestamp', true),
        staleAt: dateTimeProp('CoT stale timestamp', false),
        receivedAt: dateTimeProp('Gateway ingestion timestamp', true),
      },
      interfaces: {},
    },
    {
      apiName: 'OntologyScenario',
      displayName: 'Scenario',
      description: 'A persisted proposal and its review lifecycle metadata.',
      primaryKey: 'id',
      titleProperty: 'name',
      groups: ['GovernanceAndProcess'],
      interfaces: {
        Scenario: {
          properties: Object.fromEntries(Object.keys(scenarioProperties).map((k) => [k, k])),
          links: {},
          actions: {},
        },
      },
      properties: {
        ...scenarioProperties,
        release: textProp('Pinned ontology publication'),
        kind: textProp('Scenario storage kind'),
        description: textProp('Author supplied description'),
      },
    },
  ],

  linkTypes: [
    {
      apiName: 'EventTransferLegs',
      description: 'Connects an operational event to its ground transfer legs',
      from: { type: 'TransferLeg', name: 'event', cardinality: 'one' },
      to: { type: 'Event', name: 'transferLegs', cardinality: 'many' },
    },
    {
      apiName: 'TransferLegVehicle',
      description: 'Assigned vehicle for a transfer leg',
      from: { type: 'TransferLeg', name: 'vehicle', cardinality: 'one' },
      to: { type: 'Vehicle', name: 'transferLegs', cardinality: 'many' },
    },
    {
      apiName: 'ArrivalPlanTransferLeg',
      description: 'Binds an airport player reception plan to its transfer leg',
      from: { type: 'ArrivalPlan', name: 'transferLeg', cardinality: 'one' },
      to: { type: 'TransferLeg', name: 'arrivalPlan', cardinality: 'one' },
    },
    {
      apiName: 'ArrivalPlanReceptionStageLogProcess',
      description: 'State changes of ArrivalPlan.receptionStage',
      from: { type: 'ArrivalPlanReceptionStageLog', name: 'process', cardinality: 'one' },
      to: { type: 'ArrivalPlan', name: 'receptionStageLogs', cardinality: 'many' },
    },
    {
      apiName: 'TransferLegFlightObservation',
      description: 'Links a transfer leg to its latest HKIA flight observation (read-only evidence)',
      from: { type: 'TransferLeg', name: 'flightObservation', cardinality: 'one' },
      to: { type: 'FlightObservation', name: 'transferLegs', cardinality: 'many' },
    },
    {
      apiName: 'TransferLegTakObservations',
      description: 'Links external TAK CoT observations correlated to a transfer leg',
      from: { type: 'TakObservation', name: 'transferLeg', cardinality: 'one' },
      to: { type: 'TransferLeg', name: 'takObservations', cardinality: 'many' },
    },
    {
      apiName: 'SessionRoom',
      description: 'Assigned room for a conference session',
      from: { type: 'Session', name: 'room', cardinality: 'one' },
      to: { type: 'Room', name: 'sessions', cardinality: 'many' },
    },
  ],

  functionTypes: [
    {
      apiName: 'CalculateReceptionWindow',
      description: 'Pure calculation of earliest/latest airport meeting window from a FlightObservation',
      parameters: {
        type: 'object',
        properties: {
          flightStatus: { type: 'string' },
          anchorIso: { type: 'string', format: 'date-time' },
          sourceTimeIso: { type: 'string', format: 'date-time' },
          taxiMinutes: { type: 'integer', minimum: 0 },
          exitMinMinutes: { type: 'integer', minimum: 0 },
          exitMaxMinutes: { type: 'integer', minimum: 0 },
          freshMinutes: { type: 'integer', minimum: 1 },
        },
        required: ['flightStatus', 'anchorIso', 'sourceTimeIso'],
        additionalProperties: false,
      },
      returns: {
        type: 'object',
        properties: {
          windowFrom: { type: ['string', 'null'] },
          windowTo: { type: ['string', 'null'] },
          validUntil: { type: ['string', 'null'] },
          basis: { type: 'string' },
        },
        required: ['windowFrom', 'windowTo', 'validUntil', 'basis'],
      },
    },
    {
      apiName: 'EvaluateHotelRisk',
      description: 'Pure evaluation of whether projected hotel arrival crosses the latest check-in cutoff',
      parameters: {
        type: 'object',
        properties: {
          windowToIso: { type: 'string', format: 'date-time' },
          transferMinutes: { type: 'integer', minimum: 5 },
          latestCheckInHhMm: { type: ['string', 'null'] },
          hotelConfirmed: { type: 'boolean' },
        },
        required: ['windowToIso', 'transferMinutes', 'hotelConfirmed'],
        additionalProperties: false,
      },
      returns: {
        type: 'object',
        properties: {
          lateRisk: { type: 'boolean' },
          requiresRoomHoldAlert: { type: 'boolean' },
          estimatedHotelArrivalIso: { type: 'string' },
        },
        required: ['lateRisk', 'requiresRoomHoldAlert', 'estimatedHotelArrivalIso'],
      },
    },
    {
      apiName: 'CheckVehicleCapacity',
      description: 'Submission criterion verifying resource readiness and seat capacity before assignment',
      parameters: {
        type: 'object',
        properties: {
          requiredCapacity: { type: 'integer', minimum: 1 },
          resourceCapacity: { type: 'integer', minimum: 1 },
          resourceReadiness: { type: 'string' },
        },
        required: ['requiredCapacity', 'resourceCapacity', 'resourceReadiness'],
        additionalProperties: false,
      },
      returns: { type: 'boolean' },
    },
    {
      apiName: 'CanTransitionReceptionStage',
      description: 'Submission criterion validating legal Machinery stage transitions and hotel confirmation gate',
      parameters: {
        type: 'object',
        properties: {
          currentStage: { type: 'string' },
          targetStage: { type: 'string' },
          hotelConfirmed: { type: 'boolean' },
        },
        required: ['currentStage', 'targetStage', 'hotelConfirmed'],
        additionalProperties: false,
      },
      returns: { type: 'boolean' },
    },
  ],

  actionTypes: [
    {
      apiName: 'AcceptTransfer',
      description: 'Driver acknowledges assignment to a TransferLeg (does not confirm physical boarding or handoff)',
      parameters: {
        type: 'object',
        properties: {
          transfer: { type: 'string', minLength: 1 },
          expectedRevision: { type: 'integer', minimum: 1 },
        },
        required: ['transfer', 'expectedRevision'],
        additionalProperties: false,
      },
      objectParameters: { transfer: 'TransferLeg' },
      submissionCriteria: [],
      rules: [
        {
          op: 'editObject',
          object: input('transfer'),
          expectedRevision: input('expectedRevision'),
          properties: { dispatchState: lit('acknowledged') },
        },
      ],
      result: { type: 'TransferLeg', id: input('transfer') },
      effects: [
        {
          name: 'notifyTransferAccepted',
          handler: 'NotifyChannelFeed',
          payload: {
            objectType: lit('TransferLeg'),
            objectId: input('transfer'),
            action: lit('AcceptTransfer'),
          },
        },
      ],
    },
    {
      apiName: 'DeclineTransfer',
      description: 'Operator marks inability to respond to a TransferLeg',
      parameters: {
        type: 'object',
        properties: {
          transfer: { type: 'string', minLength: 1 },
          expectedRevision: { type: 'integer', minimum: 1 },
          reason: { type: 'string', maxLength: 300 },
        },
        required: ['transfer', 'expectedRevision'],
        additionalProperties: false,
      },
      objectParameters: { transfer: 'TransferLeg' },
      submissionCriteria: [],
      rules: [
        {
          op: 'editObject',
          object: input('transfer'),
          expectedRevision: input('expectedRevision'),
          properties: {
            dispatchState: lit('declined'),
            declineReason: input('reason'),
          },
        },
      ],
      result: { type: 'TransferLeg', id: input('transfer') },
      effects: [
        {
          name: 'notifyTransferDeclined',
          handler: 'NotifyChannelFeed',
          payload: {
            objectType: lit('TransferLeg'),
            objectId: input('transfer'),
            action: lit('DeclineTransfer'),
          },
        },
      ],
    },
    {
      apiName: 'AssignVehicle',
      description: 'Dispatcher assigns or replaces a Vehicle on a TransferLeg with native time-window reservation',
      parameters: {
        type: 'object',
        properties: {
          transfer: { type: 'string', minLength: 1 },
          expectedRevision: { type: 'integer', minimum: 1 },
          vehicle: { type: 'string', minLength: 1 },
          allocations: {
            type: 'array',
            items: {
              type: 'object',
              properties: {
                resourceId: { type: 'string', minLength: 1 },
                startAt: { type: 'string', format: 'date-time' },
                endAt: { type: 'string', format: 'date-time' },
              },
              required: ['resourceId', 'startAt', 'endAt'],
            },
            minItems: 1,
          },
        },
        required: ['transfer', 'expectedRevision', 'vehicle', 'allocations'],
        additionalProperties: false,
      },
      objectParameters: { transfer: 'TransferLeg', vehicle: 'Vehicle' },
      submissionCriteria: [
        {
          function: 'CheckVehicleCapacity',
          arguments: {
            requiredCapacity: { parameter: 'transfer', path: ['passengers'] },
            resourceCapacity: { parameter: 'vehicle', path: ['seats'] },
            resourceReadiness: { parameter: 'vehicle', path: ['readiness'] },
          },
          message: 'Selected vehicle is unavailable or has insufficient seat capacity',
        },
      ],
      reservation: {
        holderParameter: 'transfer',
        allocationsParameter: 'allocations',
        state: 'committed',
      },
      rules: [
        {
          op: 'editObject',
          object: input('transfer'),
          expectedRevision: input('expectedRevision'),
          properties: { vehicleId: input('vehicle') },
        },
        {
          op: 'addLink',
          link: 'TransferLegVehicle',
          from: { type: 'TransferLeg', id: input('transfer') },
          to: { type: 'Vehicle', id: input('vehicle') },
        },
      ],
      result: { type: 'TransferLeg', id: input('transfer') },
      effects: [
        {
          name: 'dispatchVehicleChangeRns',
          handler: 'DispatchRnsEnvelope',
          payload: {
            kind: lit('task'),
            transferId: input('transfer'),
            vehicleId: input('vehicle'),
          },
        },
        {
          name: 'notifyVehicleAssigned',
          handler: 'NotifyChannelFeed',
          payload: {
            objectType: lit('TransferLeg'),
            objectId: input('transfer'),
            action: lit('AssignVehicle'),
          },
        },
      ],
    },
    {
      apiName: 'ReschedulePickup',
      description: 'Dispatcher updates confirmed ground pickup time (typically approved via Scenario)',
      parameters: {
        type: 'object',
        properties: {
          transfer: { type: 'string', minLength: 1 },
          expectedRevision: { type: 'integer', minimum: 1 },
          pickupAt: { type: 'string', format: 'date-time' },
        },
        required: ['transfer', 'expectedRevision', 'pickupAt'],
        additionalProperties: false,
      },
      objectParameters: { transfer: 'TransferLeg' },
      submissionCriteria: [],
      rules: [
        {
          op: 'editObject',
          object: input('transfer'),
          expectedRevision: input('expectedRevision'),
          properties: { pickupAt: input('pickupAt') },
        },
      ],
      result: { type: 'TransferLeg', id: input('transfer') },
      effects: [
        {
          name: 'notifyPickupRescheduled',
          handler: 'NotifyChannelFeed',
          payload: {
            objectType: lit('TransferLeg'),
            objectId: input('transfer'),
            action: lit('ReschedulePickup'),
          },
        },
      ],
    },
    {
      apiName: 'AdvanceReceptionStage',
      description: 'Confirmed field operator transition of ArrivalPlan.receptionStage governed by Machinery',
      parameters: {
        type: 'object',
        properties: {
          plan: { type: 'string', minLength: 1 },
          expectedRevision: { type: 'integer', minimum: 1 },
          targetStage: {
            type: 'string',
            enum: ['met', 'boarded', 'hotelHandoff', 'checkedIn'],
          },
        },
        required: ['plan', 'expectedRevision', 'targetStage'],
        additionalProperties: false,
      },
      objectParameters: { plan: 'ArrivalPlan' },
      submissionCriteria: [
        {
          function: 'CanTransitionReceptionStage',
          arguments: {
            currentStage: { parameter: 'plan', path: ['receptionStage'] },
            targetStage: input('targetStage'),
            hotelConfirmed: { parameter: 'plan', path: ['hotelConfirmed'] },
          },
          message: 'Stage transition violates reception Machinery order or requires hotel confirmation',
        },
      ],
      rules: [
        {
          op: 'editObject',
          object: input('plan'),
          expectedRevision: input('expectedRevision'),
          properties: { receptionStage: input('targetStage') },
        },
      ],
      result: { type: 'ArrivalPlan', id: input('plan') },
      effects: [
        {
          name: 'dispatchStageToTak',
          handler: 'DispatchTakCot',
          payload: {
            planId: input('plan'),
            stage: input('targetStage'),
          },
        },
        {
          name: 'notifyStageAdvanced',
          handler: 'NotifyChannelFeed',
          payload: {
            objectType: lit('ArrivalPlan'),
            objectId: input('plan'),
            action: lit('AdvanceReceptionStage'),
          },
        },
      ],
    },
    {
      apiName: 'ConfirmHotelReservation',
      description: 'Dispatcher confirms or revokes hotel reservation gate on an ArrivalPlan',
      parameters: {
        type: 'object',
        properties: {
          plan: { type: 'string', minLength: 1 },
          expectedRevision: { type: 'integer', minimum: 1 },
          confirmed: { type: 'boolean' },
        },
        required: ['plan', 'expectedRevision', 'confirmed'],
        additionalProperties: false,
      },
      objectParameters: { plan: 'ArrivalPlan' },
      submissionCriteria: [],
      rules: [
        {
          op: 'editObject',
          object: input('plan'),
          expectedRevision: input('expectedRevision'),
          properties: { hotelConfirmed: input('confirmed') },
        },
      ],
      result: { type: 'ArrivalPlan', id: input('plan') },
    },
    {
      apiName: 'RecordFlightObservation',
      description: 'Ingest external HKIA flight observation without mutating TransferLeg or ArrivalPlan',
      parameters: {
        type: 'object',
        properties: {
          id: { type: 'string', minLength: 1 },
          eventId: { type: 'string', minLength: 1 },
          flightNumber: { type: 'string', minLength: 2 },
          serviceDate: { type: 'string', minLength: 10 },
          flightStatus: {
            type: 'string',
            enum: ['scheduled', 'estimated', 'delayed', 'landed', 'atGate', 'cancelled', 'other'],
          },
          scheduledAt: { type: 'string', format: 'date-time' },
          estimatedAt: { type: 'string', format: 'date-time' },
          actualAt: { type: 'string', format: 'date-time' },
          sourceTime: { type: 'string', format: 'date-time' },
          recordedAt: { type: 'string', format: 'date-time' },
        },
        required: ['id', 'eventId', 'flightNumber', 'serviceDate', 'flightStatus', 'sourceTime', 'recordedAt'],
        additionalProperties: false,
      },
      objectParameters: {},
      submissionCriteria: [],
      rules: [
        {
          op: 'createObject',
          object: { type: 'FlightObservation', id: input('id') },
          properties: {
            id: input('id'),
            eventId: input('eventId'),
            flightNumber: input('flightNumber'),
            serviceDate: input('serviceDate'),
            flightStatus: input('flightStatus'),
            scheduledAt: input('scheduledAt'),
            estimatedAt: input('estimatedAt'),
            actualAt: input('actualAt'),
            sourceTime: input('sourceTime'),
            recordedAt: input('recordedAt'),
          },
        },
      ],
      result: { type: 'FlightObservation', id: input('id') },
    },
    {
      apiName: 'RecordTakObservation',
      description: 'Ingest external TAK CoT 2.0 observation; strictly isolated from confirmed operational facts',
      parameters: {
        type: 'object',
        properties: {
          id: { type: 'string', minLength: 1 },
          eventId: { type: 'string', minLength: 1 },
          cotUid: { type: 'string', minLength: 1 },
          cotType: { type: 'string', minLength: 1 },
          category: { type: 'string', enum: ['position', 'geochat', 'marker', 'opsPtt', 'other'] },
          callsign: { type: ['string', 'null'] },
          latitude: { type: 'number' },
          longitude: { type: 'number' },
          observedAt: { type: 'string', format: 'date-time' },
          staleAt: { type: 'string', format: 'date-time' },
          receivedAt: { type: 'string', format: 'date-time' },
        },
        required: ['id', 'eventId', 'cotUid', 'cotType', 'category', 'observedAt', 'receivedAt'],
        additionalProperties: false,
      },
      objectParameters: {},
      submissionCriteria: [],
      rules: [
        {
          op: 'createObject',
          object: { type: 'TakObservation', id: input('id') },
          properties: {
            id: input('id'),
            eventId: input('eventId'),
            cotUid: input('cotUid'),
            cotType: input('cotType'),
            category: input('category'),
            callsign: input('callsign'),
            latitude: input('latitude'),
            longitude: input('longitude'),
            observedAt: input('observedAt'),
            staleAt: input('staleAt'),
            receivedAt: input('receivedAt'),
          },
        },
      ],
      result: { type: 'TakObservation', id: input('id') },
    },
    // Conference domain actions (proving WorkItem Interface reuse)
    {
      apiName: 'AcceptSessionStewardship',
      description: 'Steward acknowledges a Conference Session',
      parameters: {
        type: 'object',
        properties: {
          session: { type: 'string', minLength: 1 },
          expectedRevision: { type: 'integer', minimum: 1 },
        },
        required: ['session', 'expectedRevision'],
        additionalProperties: false,
      },
      objectParameters: { session: 'Session' },
      submissionCriteria: [],
      rules: [
        {
          op: 'editObject',
          object: input('session'),
          expectedRevision: input('expectedRevision'),
          properties: { sessionState: lit('acknowledged') },
        },
      ],
      result: { type: 'Session', id: input('session') },
    },
    {
      apiName: 'DeclineSessionStewardship',
      description: 'Steward declines a Conference Session',
      parameters: {
        type: 'object',
        properties: {
          session: { type: 'string', minLength: 1 },
          expectedRevision: { type: 'integer', minimum: 1 },
          reason: { type: 'string', maxLength: 300 },
        },
        required: ['session', 'expectedRevision'],
        additionalProperties: false,
      },
      objectParameters: { session: 'Session' },
      submissionCriteria: [],
      rules: [
        {
          op: 'editObject',
          object: input('session'),
          expectedRevision: input('expectedRevision'),
          properties: {
            sessionState: lit('declined'),
            declineReason: input('reason'),
          },
        },
      ],
      result: { type: 'Session', id: input('session') },
    },
    {
      apiName: 'RebookSessionRoom',
      description: 'Dispatcher rebooks a Conference Session into an available Room with reservation',
      parameters: {
        type: 'object',
        properties: {
          session: { type: 'string', minLength: 1 },
          expectedRevision: { type: 'integer', minimum: 1 },
          room: { type: 'string', minLength: 1 },
          allocations: {
            type: 'array',
            items: {
              type: 'object',
              properties: {
                resourceId: { type: 'string', minLength: 1 },
                startAt: { type: 'string', format: 'date-time' },
                endAt: { type: 'string', format: 'date-time' },
              },
              required: ['resourceId', 'startAt', 'endAt'],
            },
            minItems: 1,
          },
        },
        required: ['session', 'expectedRevision', 'room', 'allocations'],
        additionalProperties: false,
      },
      objectParameters: { session: 'Session', room: 'Room' },
      submissionCriteria: [
        {
          function: 'CheckVehicleCapacity',
          arguments: {
            requiredCapacity: { parameter: 'session', path: ['attendees'] },
            resourceCapacity: { parameter: 'room', path: ['capacity'] },
            resourceReadiness: { parameter: 'room', path: ['readiness'] },
          },
          message: 'Selected room is unavailable or cannot fit expected attendees',
        },
      ],
      reservation: {
        holderParameter: 'session',
        allocationsParameter: 'allocations',
        state: 'committed',
      },
      rules: [
        {
          op: 'editObject',
          object: input('session'),
          expectedRevision: input('expectedRevision'),
          properties: { roomId: input('room') },
        },
        {
          op: 'addLink',
          link: 'SessionRoom',
          from: { type: 'Session', id: input('session') },
          to: { type: 'Room', id: input('room') },
        },
      ],
      result: { type: 'Session', id: input('session') },
    },
    // Scenario Merge + 5 immutable Foundry Scenario Review Actions
    {
      apiName: 'ApplyDispatchScenario',
      description: 'Atomically merge an approved dispatch Scenario into live TransferLeg, Vehicle, and ArrivalPlan objects',
      parameters: {
        type: 'object',
        properties: {
          scenario: { type: 'string', format: 'uuid' },
          expectedRevision: { type: 'integer', minimum: 1 },
        },
        required: ['scenario', 'expectedRevision'],
        additionalProperties: false,
      },
      objectParameters: {},
      submissionCriteria: [],
      rules: [
        {
          op: 'applyScenario',
          scenario: input('scenario'),
          expectedRevision: input('expectedRevision'),
          objectTypes: ['TransferLeg', 'Vehicle', 'ArrivalPlan'],
          linkTypes: ['TransferLegVehicle'],
        },
      ],
      result: { type: 'OntologyScenario', id: input('scenario') },
    },
    makeScenarioReviewAction(
      'SubmitScenarioReview',
      'Submit an owned Scenario revision to selected reviewers',
      {
        reviewers: {
          type: 'array',
          items: { type: 'string', minLength: 1, maxLength: 200 },
          maxItems: 100,
        },
      },
      ['reviewers'],
      { kind: 'submit', reviewers: input('reviewers') },
    ),
    makeScenarioReviewAction(
      'ApproveScenarioReview',
      'Approve the exact Scenario revision and reviewed snapshot',
      { snapshot: snapshotSchema, decision: approvalDecisionSchema },
      ['snapshot'],
      { kind: 'approve', snapshot: input('snapshot'), decision: input('decision') },
    ),
    makeScenarioReviewAction(
      'RejectScenarioReview',
      'Reject the exact Scenario revision and reviewed snapshot',
      { snapshot: snapshotSchema },
      ['snapshot'],
      { kind: 'reject', snapshot: input('snapshot') },
    ),
    makeScenarioReviewAction(
      'SubmitScenarioReviewForMerge',
      'Submit a Scenario and authorize one exact live merge Action after review',
      {
        reviewers: {
          type: 'array',
          items: { type: 'string', minLength: 1, maxLength: 200 },
          minItems: 1,
          maxItems: 100,
        },
        merge: {
          type: 'object',
          properties: { action: { type: 'string' }, input: { type: 'object' } },
          required: ['action', 'input'],
          additionalProperties: false,
        },
      },
      ['reviewers', 'merge'],
      { kind: 'submit', reviewers: input('reviewers'), authorizeMerge: input('merge') },
    ),
    makeScenarioReviewAction(
      'ApproveAndMergeScenarioReview',
      'Approve an inspected snapshot and commit its owner-authorized merge atomically',
      { snapshot: snapshotSchema, decision: approvalDecisionSchema },
      ['snapshot'],
      {
        kind: 'approve',
        snapshot: input('snapshot'),
        decision: input('decision'),
        applyAuthorizedMerge: true,
      },
    ),
  ],

  dataSources: [],

  agentProfiles: [
    {
      apiName: 'DispatchCopilot',
      terms: ['dispatch', 'pickup', 'delay', 'vehicle', 'reception', 'hotel', 'flight', 'scenario'],
      objects: ['Event', 'TransferLeg', 'Vehicle', 'ArrivalPlan', 'FlightObservation', 'TakObservation', 'OntologyScenario'],
      interfaces: ['WorkItem', 'ReservableResource', 'Scenario'],
      links: ['TransferLegVehicle', 'ArrivalPlanTransferLeg', 'TransferLegFlightObservation', 'TransferLegTakObservations'],
      functions: ['CalculateReceptionWindow', 'EvaluateHotelRisk', 'CheckVehicleCapacity'],
      actions: ['SubmitScenarioReviewForMerge'],
      instructions: [
        {
          action: 'SubmitScenarioReviewForMerge',
          text: 'Never mutate live TransferLeg or ArrivalPlan directly. Stage proposed vehicle replacements or pickup reschedules in a Scenario and submit for human dispatcher approval.',
        },
        {
          function: 'CalculateReceptionWindow',
          text: 'Always verify sourceTime freshness before proposing a pickup time reschedule.',
        },
      ],
    },
  ],
};
